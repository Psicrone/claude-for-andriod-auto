import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { join, resolve } from "node:path";

export type PermissionPolicy = "ask" | "acceptEdits" | "readonly";

export interface Project {
  name: string;
  path: string;
}

export interface Config {
  /** Where the TLS certificate and paired devices live. */
  dataDir: string;
  /**
   * "self-signed" (default): the bridge serves HTTPS itself and the phone pins the certificate.
   * "off": plain HTTP, only allowed on a loopback address behind something that terminates TLS
   * (Tailscale Serve, a Cloudflare tunnel).
   */
  tls: "self-signed" | "off";
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

  const host = env.BRIDGE_HOST ?? "127.0.0.1";
  const tls = (env.BRIDGE_TLS ?? "self-signed") as Config["tls"];
  if (tls !== "self-signed" && tls !== "off") {
    throw new Error(`BRIDGE_TLS must be self-signed or off (got "${tls}").`);
  }
  if (tls === "off" && !isLoopback(host)) {
    throw new Error(
      "BRIDGE_TLS=off is only allowed when BRIDGE_HOST is 127.0.0.1 or ::1, behind a proxy that adds HTTPS.",
    );
  }

  const permissions = (env.BRIDGE_PERMISSIONS ?? "ask") as PermissionPolicy;
  if (!["ask", "acceptEdits", "readonly"].includes(permissions)) {
    throw new Error(`BRIDGE_PERMISSIONS must be ask, acceptEdits or readonly (got "${permissions}").`);
  }

  return {
    dataDir: env.BRIDGE_DATA_DIR || join(homedir(), ".claude-auto-bridge"),
    tls,
    host,
    port: Number(env.BRIDGE_PORT ?? 8787),
    projects: parseProjects(env.BRIDGE_PROJECTS),
    permissions,
    model: env.BRIDGE_MODEL || undefined,
  };
}

export function isLoopback(host: string): boolean {
  return host === "localhost" || host === "::1" || host.startsWith("127.");
}
