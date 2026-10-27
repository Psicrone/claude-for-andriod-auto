#!/usr/bin/env node
import { createServer as createHttpServer, type IncomingMessage, type RequestListener, type Server, type ServerResponse } from "node:http";
import { createServer as createHttpsServer } from "node:https";
import { realpathSync } from "node:fs";
import { networkInterfaces } from "node:os";
import { fileURLToPath } from "node:url";
import { listSessions, query } from "@anthropic-ai/claude-agent-sdk";
import { loadConfig, type Config } from "./config.js";
import { DeviceStore, FailureLimiter, loadOrCreateCertificate, Pairing, shortFingerprint } from "./security.js";
import { TurnError, TurnManager, type QueryFn } from "./turns.js";

export const VERSION = "0.2.0";
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

export interface BridgeDeps {
  devices: DeviceStore;
  pairing: Pairing;
  limiter?: FailureLimiter;
  queryFn?: QueryFn;
  listSessionsFn?: ListSessionsFn;
}

/** The bridge's HTTP API. Every route except /health and /pair needs a paired device's token. */
export function createHandler(config: Config, deps: BridgeDeps): RequestListener {
  const turns = new TurnManager(config, deps.queryFn ?? (query as QueryFn));
  const sessions = deps.listSessionsFn ?? defaultListSessions(config);
  const limiter = deps.limiter ?? new FailureLimiter();

  return async (req, res) => {
    const address = req.socket.remoteAddress ?? "unknown";
    try {
      const url = new URL(req.url ?? "/", "https://bridge");
      if (url.pathname === "/health") return send(res, 200, { ok: true, version: VERSION });

      if (limiter.isLocked(address)) {
        return send(res, 429, { error: "Too many failed attempts. Try again in 15 minutes." });
      }

      if (req.method === "POST" && url.pathname === "/pair") {
        if (!deps.pairing.isOpen) {
          return send(res, 403, { error: "Pairing is closed. Run the bridge with --pair to get a new code." });
        }
        const body = await readJson(req);
        if (!deps.pairing.redeem(String(body.code ?? ""))) {
          limiter.fail(address);
          return send(res, 401, { error: "Wrong pairing code" });
        }
        limiter.succeed(address);
        const { device, token } = deps.devices.add(String(body.deviceName ?? "Phone"));
        console.log(`Paired "${device.name}" (${device.id}). Pairing is now closed.`);
        return send(res, 200, { token, deviceId: device.id });
      }

      const bearer = /^Bearer (.+)$/.exec(req.headers.authorization ?? "")?.[1] ?? "";
      if (!deps.devices.authenticate(bearer)) {
        limiter.fail(address);
        return send(res, 401, { error: "This device isn't paired with the bridge" });
      }
      limiter.succeed(address);

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
  };
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

/** Starts the bridge: HTTPS with the pinned self-signed certificate unless TLS is terminated in front of it. */
export async function startBridge(config: Config, options: { pair?: boolean } = {}): Promise<Server> {
  const devices = new DeviceStore(config.dataDir);
  const pairing = new Pairing();
  const handler = createHandler(config, { devices, pairing });

  let server: Server;
  let fingerprint: string | undefined;
  if (config.tls === "self-signed") {
    const tls = await loadOrCreateCertificate(config.dataDir);
    fingerprint = tls.fingerprint;
    server = createHttpsServer({ key: tls.key, cert: tls.cert, minVersion: "TLSv1.2" }, handler);
  } else {
    server = createHttpServer(handler);
  }

  await new Promise<void>((resolve) => server.listen(config.port, config.host, resolve));

  const scheme = config.tls === "off" ? "http" : "https";
  console.log(`Claude Auto bridge ${VERSION}`);
  for (const address of reachableAddresses(config.host)) console.log(`  Listening on ${scheme}://${address}:${config.port}`);
  if (fingerprint) console.log(`  Certificate: ${shortFingerprint(fingerprint)}  (SHA-256 ${fingerprint})`);
  else console.log("  TLS is off: make sure the proxy in front of this port adds HTTPS.");
  console.log(`  Projects: ${config.projects.map((p) => `${p.name} (${p.path})`).join(", ")}`);
  console.log(`  Permission policy: ${config.permissions}`);

  const paired = devices.list();
  if (options.pair || paired.length === 0) {
    const code = pairing.open();
    console.log("");
    console.log(`  Pairing code: ${code}   (valid for 10 minutes, one phone)`);
    console.log("  In the Claude Auto app enter the URL above and this code, then check the");
    console.log(`  certificate it shows matches ${fingerprint ? shortFingerprint(fingerprint) : "your proxy's certificate"}.`);
  } else {
    console.log(`  Paired devices: ${paired.map((d) => `${d.name} (${d.id})`).join(", ")}. Use --pair to add another.`);
  }
  return server;
}

function reachableAddresses(host: string): string[] {
  if (host !== "0.0.0.0" && host !== "::") return [host.includes(":") ? `[${host}]` : host];
  const found = Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && !i.internal && i.family === "IPv4")
    .map((i) => i!.address);
  return found.length ? found : ["localhost"];
}

function runDevicesCommand(config: Config, args: string[]) {
  const devices = new DeviceStore(config.dataDir);
  if (args[0] === "revoke") {
    const id = args[1] ?? "";
    console.log(devices.revoke(id) ? `Revoked ${id}.` : `No device with id "${id}".`);
    return;
  }
  const list = devices.list();
  if (list.length === 0) console.log("No paired devices.");
  for (const d of list) {
    console.log(`${d.id}  ${d.name}  paired ${d.pairedAt}${d.lastSeenAt ? `  last seen ${d.lastSeenAt}` : ""}`);
  }
}

const isMain = process.argv[1] !== undefined && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url);
if (isMain) {
  const config = loadConfig();
  const [command, ...rest] = process.argv.slice(2);
  if (command === "devices") {
    runDevicesCommand(config, rest);
  } else {
    startBridge(config, { pair: command === "--pair" || command === "pair" }).catch((err) => {
      console.error(err instanceof Error ? err.message : err);
      process.exit(1);
    });
  }
}
