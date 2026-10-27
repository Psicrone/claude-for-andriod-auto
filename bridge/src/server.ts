#!/usr/bin/env node
import { createServer, type IncomingMessage, type Server, type ServerResponse } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { realpathSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { listSessions, query } from "@anthropic-ai/claude-agent-sdk";
import { loadConfig, type Config } from "./config.js";
import { TurnError, TurnManager, type QueryFn } from "./turns.js";

export const VERSION = "0.1.0";
const MAX_BODY_BYTES = 64 * 1024;
const MAX_WAIT_MS = 30_000;

export interface SessionSummary {
  id: string;
  title: string;
  project?: string;
  cwd?: string;
  lastModified: number;
}

export type ListSessionsFn = (limit: number) => Promise<SessionSummary[]>;

export function createBridge(
  config: Config,
  deps: { queryFn?: QueryFn; listSessionsFn?: ListSessionsFn } = {},
): Server {
  const turns = new TurnManager(config, deps.queryFn ?? (query as QueryFn));
  const sessions = deps.listSessionsFn ?? defaultListSessions(config);
  const expectedAuth = Buffer.from(`Bearer ${config.token}`);

  return createServer(async (req, res) => {
    try {
      const url = new URL(req.url ?? "/", "http://bridge");
      if (url.pathname === "/health") return send(res, 200, { ok: true, version: VERSION });

      const auth = Buffer.from(req.headers.authorization ?? "");
      if (auth.length !== expectedAuth.length || !timingSafeEqual(auth, expectedAuth)) {
        return send(res, 401, { error: "Missing or wrong bridge token" });
      }

      const parts = url.pathname.split("/").filter(Boolean);
      const route = `${req.method} /${parts.map((p, i) => (i === 1 ? ":id" : p)).join("/")}`;

      switch (route) {
        case "GET /projects":
          return send(res, 200, { projects: config.projects.map(({ name, path }) => ({ name, path })) });

        case "GET /sessions": {
          const limit = clamp(Number(url.searchParams.get("limit") ?? 10), 1, 50);
          return send(res, 200, { sessions: await sessions(limit) });
        }

        case "POST /turns": {
          const body = await readJson(req);
          const view = turns.start({
            text: String(body.text ?? ""),
            sessionId: optionalString(body.sessionId),
            project: optionalString(body.project),
            cwd: optionalString(body.cwd),
          });
          return send(res, 202, view);
        }

        case "GET /turns/:id": {
          const since = Number(url.searchParams.get("since") ?? -1);
          const wait = clamp(Number(url.searchParams.get("wait") ?? 0) * 1000, 0, MAX_WAIT_MS);
          return send(res, 200, await turns.waitFor(parts[1], since, wait));
        }

        case "POST /turns/:id/approval": {
          const body = await readJson(req);
          return send(res, 200, turns.answer(parts[1], String(body.approvalId ?? ""), body.approve === true));
        }

        case "POST /turns/:id/cancel":
          return send(res, 200, turns.cancel(parts[1]));

        default:
          return send(res, 404, { error: "Not found" });
      }
    } catch (err) {
      if (err instanceof TurnError) return send(res, err.status, { error: err.message });
      if (err instanceof SyntaxError) return send(res, 400, { error: "Body must be JSON" });
      console.error(err);
      return send(res, 500, { error: err instanceof Error ? err.message : "Internal error" });
    }
  });
}

function defaultListSessions(config: Config): ListSessionsFn {
  return async (limit) => {
    // Sessions from every configured project, newest first.
    const perProject = await Promise.all(
      config.projects.map(async (project) => {
        try {
          const found = await listSessions({ dir: project.path, limit });
          return found.map((s) => ({
            id: s.sessionId,
            title: s.customTitle || s.summary || s.firstPrompt || "Untitled session",
            project: project.name,
            cwd: s.cwd ?? project.path,
            lastModified: s.lastModified,
          }));
        } catch {
          return [];
        }
      }),
    );
    const seen = new Set<string>();
    return perProject
      .flat()
      .sort((a, b) => b.lastModified - a.lastModified)
      .filter((s) => !seen.has(s.id) && seen.add(s.id))
      .slice(0, limit);
  };
}

function send(res: ServerResponse, status: number, body: unknown) {
  const json = JSON.stringify(body);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    "content-length": Buffer.byteLength(json),
  });
  res.end(json);
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  let size = 0;
  const chunks: Buffer[] = [];
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY_BYTES) throw new TurnError(413, "Body too large");
    chunks.push(chunk as Buffer);
  }
  if (chunks.length === 0) return {};
  const parsed = JSON.parse(Buffer.concat(chunks).toString("utf8"));
  if (typeof parsed !== "object" || parsed === null) throw new TurnError(400, "Body must be a JSON object");
  return parsed as Record<string, unknown>;
}

function optionalString(value: unknown): string | undefined {
  return typeof value === "string" && value.trim() ? value.trim() : undefined;
}

function clamp(n: number, min: number, max: number): number {
  return Number.isFinite(n) ? Math.min(max, Math.max(min, n)) : min;
}

const isMain = process.argv[1] !== undefined && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const config = loadConfig();
  createBridge(config).listen(config.port, config.host, () => {
    console.log(`Claude Auto bridge ${VERSION} listening on http://${config.host}:${config.port}`);
    console.log(`Projects: ${config.projects.map((p) => `${p.name} (${p.path})`).join(", ")}`);
    console.log(`Permission policy: ${config.permissions}`);
  });
}
