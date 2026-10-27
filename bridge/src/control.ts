import { existsSync, readFileSync, renameSync, unlinkSync, writeFileSync } from "node:fs";
import { networkInterfaces } from "node:os";
import { join } from "node:path";
import QRCode from "qrcode";
import type { Config } from "./config.js";

// ---------------------------------------------------------------------------
// Pairing links. The QR code the bridge shows holds everything the phone
// needs: the address, the one-time code and the full certificate
// fingerprint. Scanning it off this computer's screen is the same proof as
// typing the code, and it lets the phone pin the certificate without asking
// the user to compare codes by eye.
// ---------------------------------------------------------------------------

export function pairingLink(url: string, code: string, fingerprint?: string): string {
  const params = new URLSearchParams({ url, code });
  if (fingerprint) params.set("fp", fingerprint.replace(/:/g, "").toUpperCase());
  return `claudeauto://pair?${params.toString()}`;
}

/** The QR code for [link], drawn with half-block characters so it fits in a terminal. */
export async function terminalQr(link: string): Promise<string> {
  return QRCode.toString(link, { type: "terminal", small: true, errorCorrectionLevel: "M" });
}

// ---------------------------------------------------------------------------
// Addresses. When listening on every interface, the phone is told the
// address most likely to work from the car: Tailscale first (it works away
// from home), then the home network, then anything else.
// ---------------------------------------------------------------------------

export function reachableAddresses(host: string): string[] {
  if (host !== "0.0.0.0" && host !== "::") return [host.includes(":") ? `[${host}]` : host];
  const found = Object.values(networkInterfaces())
    .flat()
    .filter((i) => i && !i.internal && i.family === "IPv4")
    .map((i) => i!.address);
  return found.length ? sortAddresses(found) : ["localhost"];
}

export function sortAddresses(addresses: string[]): string[] {
  const rank = (address: string) => {
    const [a, b] = address.split(".").map(Number);
    if (a === 100 && b >= 64 && b <= 127) return 0; // Tailscale (CGNAT range)
    if (a === 192 && b === 168) return 1;
    if (a === 10) return 2;
    if (a === 172 && b >= 16 && b <= 31) return 3; // also Docker's bridge, so last of the private ranges
    return 4;
  };
  return [...addresses].sort((x, y) => rank(x) - rank(y));
}

/** The https:// address to put in the QR code, or undefined when only a proxy knows it. */
export function publicUrl(config: Config): string | undefined {
  if (config.publicUrl) return config.publicUrl.replace(/\/+$/, "");
  if (config.tls === "off") return undefined;
  return `https://${reachableAddresses(config.host)[0]}:${config.port}`;
}

// ---------------------------------------------------------------------------
// Control files. A bridge running as a background service (or in Docker, or
// in Termux) can be asked to open a pairing window by another
// `claude-auto-bridge pair` process. They talk through files in the private
// data directory, so only the user who owns the bridge can do it.
// ---------------------------------------------------------------------------

export interface PairingInfo {
  link: string;
  url?: string;
  code: string;
  certificate?: string;
  expiresAt: number;
  /** Set by the bridge once a phone has used the code. */
  pairedDevice?: string;
}

export interface RunningBridge {
  pid: number;
  port: number;
}

export class ControlFiles {
  readonly running: string;
  readonly request: string;
  readonly pairing: string;

  constructor(dataDir: string) {
    this.running = join(dataDir, "running.json");
    this.request = join(dataDir, "pair-request");
    this.pairing = join(dataDir, "pairing.json");
  }

  // Bridge side.

  markRunning(port: number) {
    writeAtomic(this.running, JSON.stringify({ pid: process.pid, port }));
  }

  clearRunning() {
    remove(this.running);
    remove(this.pairing);
  }

  /** True once per `pair` request from another process. */
  takePairRequest(): boolean {
    if (!existsSync(this.request)) return false;
    remove(this.request);
    return true;
  }

  publishPairing(info: PairingInfo) {
    writeAtomic(this.pairing, JSON.stringify(info, null, 2));
  }

  clearPairing() {
    remove(this.pairing);
  }

  // `claude-auto-bridge pair` side.

  /** The bridge already running for this data directory, if any. */
  runningBridge(): RunningBridge | undefined {
    const info = readJson<RunningBridge>(this.running);
    if (!info || info.pid === process.pid) return undefined;
    try {
      process.kill(info.pid, 0);
      return info;
    } catch (err) {
      // EPERM means it exists but belongs to someone else (it's still running).
      return (err as NodeJS.ErrnoException).code === "EPERM" ? info : undefined;
    }
  }

  requestPairing() {
    remove(this.pairing);
    writeAtomic(this.request, String(Date.now()));
  }

  readPairing(): PairingInfo | undefined {
    return readJson<PairingInfo>(this.pairing);
  }
}

function readJson<T>(path: string): T | undefined {
  try {
    return JSON.parse(readFileSync(path, "utf8")) as T;
  } catch {
    return undefined;
  }
}

function writeAtomic(path: string, contents: string) {
  const tmp = `${path}.${process.pid}.tmp`;
  writeFileSync(tmp, contents, { mode: 0o600 });
  renameSync(tmp, path);
}

function remove(path: string) {
  try {
    unlinkSync(path);
  } catch {
    // Already gone.
  }
}
