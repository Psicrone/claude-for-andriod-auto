import { createHash, randomBytes, randomInt, randomUUID, timingSafeEqual, X509Certificate } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { generate } from "selfsigned";

// ---------------------------------------------------------------------------
// TLS: a self-signed certificate created on first run. The phone pins its
// SHA-256 fingerprint, so no certificate authority is involved or trusted.
// ---------------------------------------------------------------------------

export interface TlsMaterial {
  key: string;
  cert: string;
  /** SHA-256 of the DER certificate, "AB:CD:..." (what the phone pins). */
  fingerprint: string;
}

export async function loadOrCreateCertificate(dataDir: string): Promise<TlsMaterial> {
  ensurePrivateDir(dataDir);
  const keyPath = join(dataDir, "bridge-key.pem");
  const certPath = join(dataDir, "bridge-cert.pem");

  if (!existsSync(keyPath) || !existsSync(certPath)) {
    const notBefore = new Date();
    const notAfter = new Date(notBefore);
    notAfter.setFullYear(notAfter.getFullYear() + 10);
    const pems = await generate([{ name: "commonName", value: "claude-auto-bridge" }], {
      keyType: "ec",
      curve: "P-256",
      algorithm: "sha256",
      notBeforeDate: notBefore,
      notAfterDate: notAfter,
    });
    writePrivate(keyPath, pems.private);
    writePrivate(certPath, pems.cert);
  }

  const cert = readFileSync(certPath, "utf8");
  return { key: readFileSync(keyPath, "utf8"), cert, fingerprint: new X509Certificate(cert).fingerprint256 };
}

/** The first 8 hex digits of a fingerprint, grouped for reading aloud: "AB12-CD34". */
export function shortFingerprint(fingerprint: string): string {
  const hex = fingerprint.replace(/:/g, "").slice(0, 8).toUpperCase();
  return `${hex.slice(0, 4)}-${hex.slice(4)}`;
}

// ---------------------------------------------------------------------------
// Paired devices. Each phone gets its own random token; only a SHA-256 hash
// is stored, so the file on disk can't be replayed. Revoking a device is
// removing its entry (`npm run devices -- revoke <id>`).
// ---------------------------------------------------------------------------

export interface Device {
  id: string;
  name: string;
  tokenHash: string;
  pairedAt: string;
  lastSeenAt?: string;
}

export class DeviceStore {
  private devices: Device[] = [];
  private loadedMtime = -1;
  private readonly path: string;

  constructor(dataDir: string) {
    ensurePrivateDir(dataDir);
    this.path = join(dataDir, "devices.json");
    this.reload();
  }

  list(): Device[] {
    this.reload();
    return [...this.devices];
  }

  /** Creates a device and returns its bearer token (shown to the phone once, never stored). */
  add(name: string): { device: Device; token: string } {
    this.reload();
    const token = randomBytes(32).toString("base64url");
    const device: Device = {
      id: randomUUID().slice(0, 8),
      name: name.slice(0, 60) || "Phone",
      tokenHash: hashToken(token),
      pairedAt: new Date().toISOString(),
    };
    this.devices.push(device);
    this.save();
    return { device, token };
  }

  revoke(id: string): boolean {
    this.reload();
    const before = this.devices.length;
    this.devices = this.devices.filter((d) => d.id !== id);
    if (this.devices.length === before) return false;
    this.save();
    return true;
  }

  /** Returns the device a bearer token belongs to, in constant time per device. */
  authenticate(token: string): Device | undefined {
    if (!token) return undefined;
    this.reload();
    const candidate = Buffer.from(hashToken(token), "hex");
    const match = this.devices.find((d) => timingSafeEqual(Buffer.from(d.tokenHash, "hex"), candidate));
    if (match) match.lastSeenAt = new Date().toISOString();
    return match;
  }

  /** Picks up edits made by `npm run devices` while the bridge is running. */
  private reload() {
    if (!existsSync(this.path)) {
      this.devices = [];
      return;
    }
    const mtime = statSync(this.path).mtimeMs;
    if (mtime === this.loadedMtime) return;
    this.devices = JSON.parse(readFileSync(this.path, "utf8")).devices ?? [];
    this.loadedMtime = mtime;
  }

  private save() {
    const tmp = `${this.path}.tmp`;
    writePrivate(tmp, JSON.stringify({ devices: this.devices }, null, 2));
    renameSync(tmp, this.path);
    this.loadedMtime = statSync(this.path).mtimeMs;
  }
}

function hashToken(token: string): string {
  return createHash("sha256").update(token).digest("hex");
}

// ---------------------------------------------------------------------------
// Pairing: a one-time code printed in the bridge's terminal, valid for ten
// minutes and five attempts. Typing it into the phone proves the phone's
// owner can see this computer's screen.
// ---------------------------------------------------------------------------

const PAIRING_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no 0/O/1/I
const PAIRING_TTL_MS = 10 * 60_000;
const PAIRING_MAX_ATTEMPTS = 5;

export class Pairing {
  private code?: string;
  private expiresAt = 0;
  private attempts = 0;

  constructor(private readonly now: () => number = Date.now) {}

  /** Starts a new pairing window and returns the code to display, e.g. "K7QM-4XPA". */
  open(): string {
    const chars = Array.from({ length: 8 }, () => PAIRING_ALPHABET[randomInt(PAIRING_ALPHABET.length)]);
    this.code = `${chars.slice(0, 4).join("")}-${chars.slice(4).join("")}`;
    this.expiresAt = this.now() + PAIRING_TTL_MS;
    this.attempts = 0;
    return this.code;
  }

  get isOpen(): boolean {
    return this.code !== undefined && this.now() < this.expiresAt && this.attempts < PAIRING_MAX_ATTEMPTS;
  }

  /** Checks a code; a correct code closes the window so it can't be reused. */
  redeem(input: string): boolean {
    if (!this.isOpen || !this.code) return false;
    this.attempts++;
    const given = Buffer.from(normaliseCode(input));
    const expected = Buffer.from(normaliseCode(this.code));
    const ok = given.length === expected.length && timingSafeEqual(given, expected);
    if (ok) this.code = undefined;
    return ok;
  }
}

function normaliseCode(code: string): string {
  return code.toUpperCase().replace(/[^A-Z0-9]/g, "");
}

// ---------------------------------------------------------------------------
// Brute-force protection: after repeated bad tokens or codes from one
// address, that address is locked out for a while.
// ---------------------------------------------------------------------------

export class FailureLimiter {
  private failures = new Map<string, { count: number; lockedUntil: number }>();

  constructor(
    private readonly maxFailures = 10,
    private readonly lockMs = 15 * 60_000,
    private readonly now: () => number = Date.now,
  ) {}

  isLocked(address: string): boolean {
    const entry = this.failures.get(address);
    return entry !== undefined && entry.lockedUntil > this.now();
  }

  fail(address: string) {
    const entry = this.failures.get(address) ?? { count: 0, lockedUntil: 0 };
    entry.count++;
    if (entry.count >= this.maxFailures) {
      entry.lockedUntil = this.now() + this.lockMs;
      entry.count = 0;
    }
    this.failures.set(address, entry);
  }

  succeed(address: string) {
    this.failures.delete(address);
  }
}

function ensurePrivateDir(dir: string) {
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true, mode: 0o700 });
}

function writePrivate(path: string, contents: string) {
  writeFileSync(path, contents, { mode: 0o600 });
  chmodSync(path, 0o600);
}
