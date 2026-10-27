import { existsSync } from "node:fs";
import { resolve } from "node:path";

export type PermissionPolicy = "ask" | "acceptEdits" | "readonly";

export interface Project {
  name: string;
  path: string;
}

export interface Config {
  token: string;
  host: string;
  port: number;
  projects: Project[];
  permissions: PermissionPolicy;
  model?: string;
}

export function parseProjects(raw: string | undefined): Project[] {
  if (!raw) return [{ name: "home", path: process.cwd() }];
  return raw
    .split(";")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .map((entry) => {
      const eq = entry.indexOf("=");
      if (eq < 0) return { name: entry.split("/").filter(Boolean).pop() ?? entry, path: entry };
      return { name: entry.slice(0, eq).trim(), path: entry.slice(eq + 1).trim() };
    });
}

export function loadConfig(env: NodeJS.ProcessEnv = process.env): Config {
  const envFile = resolve(process.cwd(), ".env");
  if (env === process.env && existsSync(envFile)) {
    // Available on Node 20.12+; values already in the environment win.
    process.loadEnvFile?.(envFile);
  }

  const token = env.BRIDGE_TOKEN ?? "";
  if (token.length < 16 || token === "change-me") {
    throw new Error(
      "BRIDGE_TOKEN must be set to a random secret of at least 16 characters (see .env.example).",
    );
  }

  const permissions = (env.BRIDGE_PERMISSIONS ?? "ask") as PermissionPolicy;
  if (!["ask", "acceptEdits", "readonly"].includes(permissions)) {
    throw new Error(`BRIDGE_PERMISSIONS must be ask, acceptEdits or readonly (got "${permissions}").`);
  }

  return {
    token,
    host: env.BRIDGE_HOST ?? "127.0.0.1",
    port: Number(env.BRIDGE_PORT ?? 8787),
    projects: parseProjects(env.BRIDGE_PROJECTS),
    permissions,
    model: env.BRIDGE_MODEL || undefined,
  };
}
