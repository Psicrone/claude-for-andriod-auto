import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { request as httpsRequest, createServer as createHttpsServer } from "node:https";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { TLSSocket } from "node:tls";
import type { Options, SDKMessage } from "@anthropic-ai/claude-agent-sdk";
import { createHandler } from "../server.js";
import type { Config } from "../config.js";
import { parseProjects } from "../config.js";
import { DeviceStore, FailureLimiter, loadOrCreateCertificate, Pairing } from "../security.js";
import { toSpeech } from "../speech.js";
import type { QueryFn } from "../turns.js";

const dataDir = mkdtempSync(join(tmpdir(), "claude-auto-test-"));
const config: Config = {
  dataDir,
  tls: "self-signed",
  host: "127.0.0.1",
  port: 0,
  projects: [{ name: "work", path: "/tmp" }],
  permissions: "ask",
};

/** A stand-in for Claude Code: asks to run one Bash command, then replies. */
const fakeQuery: QueryFn = ({ prompt, options }: { prompt: string; options?: Options }) =>
  (async function* () {
    const sessionId = options?.resume ?? "session-1";
    yield { type: "system", subtype: "init", session_id: sessionId } as unknown as SDKMessage;
    yield {
      type: "assistant",
      parent_tool_use_id: null,
      session_id: sessionId,
      message: { content: [{ type: "tool_use", id: "t1", name: "Bash", input: { command: "npm test" } }] },
    } as unknown as SDKMessage;
    const decision = await options!.canUseTool!("Bash", { command: "npm test" }, {
      signal: options!.abortController!.signal,
    } as never);
    yield {
      type: "result",
      subtype: "success",
      is_error: false,
      session_id: sessionId,
      total_cost_usd: 0.01,
      result: `**Done.** You said "${prompt}". Tests ${decision?.behavior === "allow" ? "passed" : "were skipped"}.\n\n\`\`\`\nok\n\`\`\``,
    } as unknown as SDKMessage;
  })();

/**
 * Starts an HTTPS bridge with one paired device. Requests are made the way the phone makes
 * them: no certificate authority, only the pinned SHA-256 fingerprint is trusted.
 */
async function startBridge(options: { pairingCode?: boolean; limiter?: FailureLimiter } = {}) {
  const tls = await loadOrCreateCertificate(dataDir);
  const devices = new DeviceStore(mkdtempSync(join(tmpdir(), "claude-auto-devices-")));
  const { token } = devices.add("Test phone");
  const pairing = new Pairing();
  const code = options.pairingCode ? pairing.open() : undefined;
  const server = createHttpsServer(
    { key: tls.key, cert: tls.cert },
    createHandler(config, {
      devices,
      pairing,
      limiter: options.limiter,
      queryFn: fakeQuery,
      listSessionsFn: async () => [{ id: "session-1", title: "Fix login", project: "work", lastModified: 1 }],
    }),
  );
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;

  const call = (method: string, path: string, body?: unknown, bearer: string | null = token, pin = tls.fingerprint) =>
    new Promise<{ status: number; json: any }>((resolve, reject) => {
      const req = httpsRequest(
        {
          host: "127.0.0.1",
          port,
          path,
          method,
          headers: {
            ...(bearer ? { authorization: `Bearer ${bearer}` } : {}),
            "content-type": "application/json",
          },
          // Same trust rule as the app: accept the certificate only if its fingerprint matches.
          rejectUnauthorized: false,
          agent: false,
        },
        (res) => {
          const peer = (res.socket as TLSSocket).getPeerCertificate();
          if (peer.fingerprint256 !== pin) {
            res.destroy();
            return reject(new Error("certificate fingerprint mismatch"));
          }
          let text = "";
          res.on("data", (chunk) => (text += chunk));
          res.on("end", () => resolve({ status: res.statusCode ?? 0, json: JSON.parse(text || "{}") }));
        },
      );
      req.on("error", reject);
      if (body !== undefined) req.write(JSON.stringify(body));
      req.end();
    });
  return { server, call, code, devices };
}

test("rejects devices that aren't paired", async () => {
  const { server, call } = await startBridge();
  try {
    assert.equal((await call("GET", "/health", undefined, null)).status, 200);
    assert.equal((await call("GET", "/sessions", undefined, null)).status, 401);
    assert.equal((await call("GET", "/sessions", undefined, "not-a-device-token")).status, 401);
  } finally {
    server.close();
  }
});

test("a phone pinning a different certificate refuses to talk", async () => {
  const { server, call } = await startBridge();
  try {
    await assert.rejects(call("GET", "/health", undefined, null, "00:11:22"), /fingerprint mismatch/);
  } finally {
    server.close();
  }
});

test("pairing with the one-time code issues a device token", async () => {
  const { server, call, code, devices } = await startBridge({ pairingCode: true });
  try {
    assert.equal((await call("POST", "/pair", { code: "WRONG-CODE" }, null)).status, 401);
    const paired = await call("POST", "/pair", { code: code!.toLowerCase(), deviceName: "Pixel" }, null);
    assert.equal(paired.status, 200);
    assert.equal((await call("GET", "/projects", undefined, paired.json.token)).status, 200);
    assert.equal(devices.list().length, 2);

    // The code works once.
    assert.equal((await call("POST", "/pair", { code }, null)).status, 403);

    // Revoking the device locks it out.
    devices.revoke(paired.json.deviceId);
    assert.equal((await call("GET", "/projects", undefined, paired.json.token)).status, 401);
  } finally {
    server.close();
  }
});

test("repeated bad tokens lock the address out", async () => {
  const { server, call } = await startBridge({ limiter: new FailureLimiter(3) });
  try {
    for (let i = 0; i < 3; i++) await call("GET", "/sessions", undefined, "guess");
    assert.equal((await call("GET", "/sessions")).status, 429);
  } finally {
    server.close();
  }
});

test("lists sessions and projects", async () => {
  const { server, call } = await startBridge();
  try {
    assert.deepEqual((await call("GET", "/projects")).json.projects, [{ name: "work", path: "/tmp" }]);
    assert.equal((await call("GET", "/sessions")).json.sessions[0].title, "Fix login");
  } finally {
    server.close();
  }
});

test("a turn waits for voice approval, then returns a speakable reply", async () => {
  const { server, call } = await startBridge();
  try {
    const started = await call("POST", "/turns", { text: "run the tests" });
    assert.equal(started.status, 202);
    const id = started.json.id;

    let view = (await call("GET", `/turns/${id}?since=-1&wait=5`)).json;
    while (view.state === "running") view = (await call("GET", `/turns/${id}?since=${view.version}&wait=5`)).json;
    assert.equal(view.state, "awaiting_approval");
    assert.equal(view.approval.summary, "run the command npm test");

    const busy = await call("POST", "/turns", { text: "again", sessionId: view.sessionId });
    assert.equal(busy.status, 409);

    await call("POST", `/turns/${id}/approval`, { approvalId: view.approval.id, approve: true });
    while (view.state !== "done") view = (await call("GET", `/turns/${id}?since=${view.version}&wait=5`)).json;

    assert.equal(view.sessionId, "session-1");
    assert.match(view.reply, /Tests passed/);
    assert.equal(view.speech, 'Done. You said "run the tests". Tests passed. (code block omitted)');
  } finally {
    server.close();
  }
});

test("declining an approval tells Claude no", async () => {
  const { server, call } = await startBridge();
  try {
    const id = (await call("POST", "/turns", { text: "hi", sessionId: "s-9" })).json.id;
    let view = (await call("GET", `/turns/${id}?since=-1&wait=5`)).json;
    while (view.state !== "awaiting_approval") view = (await call("GET", `/turns/${id}?since=${view.version}&wait=5`)).json;
    await call("POST", `/turns/${id}/approval`, { approvalId: view.approval.id, approve: false });
    while (view.state !== "done") view = (await call("GET", `/turns/${id}?since=${view.version}&wait=5`)).json;
    assert.equal(view.sessionId, "s-9");
    assert.match(view.reply, /skipped/);
  } finally {
    server.close();
  }
});

test("toSpeech strips markdown and trims long replies", () => {
  assert.equal(toSpeech("# Title\n- one\n- two\nSee [docs](https://x.y)"), "Title one. two. See docs");
  const long = toSpeech("Sentence here. ".repeat(200), 100);
  assert.ok(long.endsWith("The rest is on your phone."));
  assert.ok(long.length < 140);
});

test("parseProjects reads name=path pairs", () => {
  assert.deepEqual(parseProjects("a=/x;b=/y/z"), [
    { name: "a", path: "/x" },
    { name: "b", path: "/y/z" },
  ]);
  assert.deepEqual(parseProjects("/srv/app"), [{ name: "app", path: "/srv/app" }]);
});
