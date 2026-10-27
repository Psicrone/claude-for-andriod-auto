import { test } from "node:test";
import assert from "node:assert/strict";
import type { AddressInfo } from "node:net";
import type { Options, SDKMessage } from "@anthropic-ai/claude-agent-sdk";
import { createBridge } from "../server.js";
import type { Config } from "../config.js";
import { parseProjects } from "../config.js";
import { toSpeech } from "../speech.js";
import type { QueryFn } from "../turns.js";

const TOKEN = "test-token-0123456789";
const config: Config = {
  token: TOKEN,
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

async function startBridge() {
  const server = createBridge(config, {
    queryFn: fakeQuery,
    listSessionsFn: async () => [{ id: "session-1", title: "Fix login", project: "work", lastModified: 1 }],
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as AddressInfo;
  const call = async (method: string, path: string, body?: unknown, token = TOKEN) => {
    const res = await fetch(`http://127.0.0.1:${port}${path}`, {
      method,
      headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    return { status: res.status, json: (await res.json()) as any };
  };
  return { server, call };
}

test("rejects requests without the token", async () => {
  const { server, call } = await startBridge();
  try {
    assert.equal((await call("GET", "/health", undefined, "nope")).status, 200);
    assert.equal((await call("GET", "/sessions", undefined, "wrong-token")).status, 401);
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
