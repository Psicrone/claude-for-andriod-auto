import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseEnvFile, uniqueNames } from "../cli.js";
import { ControlFiles, pairingLink, publicUrl, sortAddresses } from "../control.js";
import type { Config } from "../config.js";

test("pairing links carry the address, code and full fingerprint", () => {
  const link = pairingLink("https://100.101.102.103:8787", "LKPS-JP8N", "95:2f:A2:3C");
  const url = new URL(link);
  assert.equal(url.protocol, "claudeauto:");
  assert.equal(url.searchParams.get("url"), "https://100.101.102.103:8787");
  assert.equal(url.searchParams.get("code"), "LKPS-JP8N");
  assert.equal(url.searchParams.get("fp"), "952FA23C");
  assert.equal(new URL(pairingLink("https://bridge.example.ts.net", "AAAA-BBBB")).searchParams.has("fp"), false);
});

test("Tailscale addresses come first, then the home network", () => {
  assert.deepEqual(sortAddresses(["172.17.0.1", "203.0.113.5", "192.168.1.20", "100.90.1.2", "10.0.0.4"]), [
    "100.90.1.2",
    "192.168.1.20",
    "10.0.0.4",
    "172.17.0.1",
    "203.0.113.5",
  ]);
});

test("BRIDGE_PUBLIC_URL wins, and a TLS-off bridge has no address of its own", () => {
  const base: Config = { dataDir: "/tmp", tls: "self-signed", host: "127.0.0.1", port: 8787, projects: [], permissions: "ask" };
  assert.equal(publicUrl(base), "https://127.0.0.1:8787");
  assert.equal(publicUrl({ ...base, publicUrl: "https://pi.tail1234.ts.net/" }), "https://pi.tail1234.ts.net");
  assert.equal(publicUrl({ ...base, tls: "off" }), undefined);
});

test("another process can ask a running bridge to open pairing", () => {
  const dir = mkdtempSync(join(tmpdir(), "claude-auto-control-"));
  const bridge = new ControlFiles(dir);
  const cli = new ControlFiles(dir);

  assert.equal(cli.runningBridge(), undefined);
  // A process that has exited is not a running bridge.
  bridge.markRunning(8787);
  assert.equal(cli.runningBridge(), undefined, "our own pid doesn't count");

  cli.requestPairing();
  assert.equal(bridge.takePairRequest(), true);
  assert.equal(bridge.takePairRequest(), false, "a request is taken once");

  bridge.publishPairing({ link: "claudeauto://pair?x", code: "AAAA-BBBB", expiresAt: 1 });
  assert.equal(cli.readPairing()?.code, "AAAA-BBBB");
  bridge.publishPairing({ link: "claudeauto://pair?x", code: "AAAA-BBBB", expiresAt: 1, pairedDevice: "Pixel" });
  assert.equal(cli.readPairing()?.pairedDevice, "Pixel");
  bridge.clearRunning();
  assert.equal(cli.readPairing(), undefined);
});

test("setup names projects after their folders", () => {
  assert.deepEqual(uniqueNames(["/home/me/code/app", "/work/app", "/home/me/blog"]), [
    "app=/home/me/code/app",
    "app-2=/work/app",
    "blog=/home/me/blog",
  ]);
});

test("setup keeps existing settings", () => {
  assert.deepEqual(parseEnvFile("# comment\nBRIDGE_PORT=9000\nANTHROPIC_API_KEY=\"sk-ant-x\"\r\n\n"), {
    BRIDGE_PORT: "9000",
    ANTHROPIC_API_KEY: "sk-ant-x",
  });
});
