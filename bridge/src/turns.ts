import { randomUUID } from "node:crypto";
import type {
  CanUseTool,
  Options,
  PermissionResult,
  Query,
  SDKMessage,
} from "@anthropic-ai/claude-agent-sdk";
import type { Config, Project } from "./config.js";
import { describeTool, toSpeech } from "./speech.js";

export type TurnState = "running" | "awaiting_approval" | "done" | "error" | "cancelled";

export interface PendingApproval {
  id: string;
  tool: string;
  summary: string;
}

export interface TurnView {
  id: string;
  version: number;
  state: TurnState;
  sessionId?: string;
  project: string;
  prompt: string;
  progress: string;
  approval?: PendingApproval;
  reply?: string;
  speech?: string;
  error?: string;
  costUsd?: number;
}

export type QueryFn = (params: { prompt: string; options?: Options }) => Query | AsyncIterable<SDKMessage>;

const READ_ONLY_TOOLS = new Set(["Read", "Glob", "Grep", "LS", "NotebookRead", "TodoWrite"]);
const EDIT_TOOLS = new Set(["Edit", "MultiEdit", "Write", "NotebookEdit"]);
const APPROVAL_TIMEOUT_MS = 5 * 60_000;
const TURN_TTL_MS = 60 * 60_000;

export const DRIVING_PROMPT = [
  "The user is driving and talks to you through a car voice app.",
  "Their messages come from speech recognition, so expect missing punctuation and misheard words; infer the intent.",
  "Your final reply is read aloud by text-to-speech: answer in two to four short spoken sentences,",
  "with no Markdown, tables, code blocks or file paths unless they asked for one.",
  "Summarise what you changed or found rather than showing it.",
  "Any tool beyond reading files needs their spoken approval, so batch risky work into few steps.",
].join(" ");

class Turn {
  readonly id = randomUUID();
  version = 0;
  state: TurnState = "running";
  sessionId?: string;
  progress = "Thinking";
  approval?: PendingApproval;
  reply?: string;
  error?: string;
  costUsd?: number;
  finishedAt?: number;
  readonly abort = new AbortController();
  private approvalResolver?: (approved: boolean) => void;
  private waiters: Array<() => void> = [];

  constructor(
    readonly project: Project,
    readonly prompt: string,
    resumeId?: string,
  ) {
    this.sessionId = resumeId;
  }

  bump(changes: Partial<Pick<Turn, "state" | "progress" | "sessionId" | "reply" | "error" | "costUsd">>) {
    Object.assign(this, changes);
    if (changes.state && changes.state !== "running" && changes.state !== "awaiting_approval") {
      this.finishedAt = Date.now();
    }
    this.version++;
    const waiters = this.waiters;
    this.waiters = [];
    waiters.forEach((wake) => wake());
  }

  waitForChange(sinceVersion: number, timeoutMs: number): Promise<void> {
    if (this.version > sinceVersion || timeoutMs <= 0) return Promise.resolve();
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.waiters = this.waiters.filter((w) => w !== wake);
        resolve();
      }, timeoutMs);
      const wake = () => {
        clearTimeout(timer);
        resolve();
      };
      this.waiters.push(wake);
    });
  }

  requestApproval(tool: string, summary: string, signal: AbortSignal): Promise<boolean> {
    return new Promise((resolve) => {
      const previousProgress = this.progress;
      const finish = (approved: boolean) => {
        clearTimeout(timer);
        signal.removeEventListener("abort", onAbort);
        this.approvalResolver = undefined;
        this.approval = undefined;
        if (this.state === "awaiting_approval") {
          this.bump({ state: "running", progress: approved ? `Going to ${summary}` : previousProgress });
        }
        resolve(approved);
      };
      const onAbort = () => finish(false);
      const timer = setTimeout(() => finish(false), APPROVAL_TIMEOUT_MS);
      signal.addEventListener("abort", onAbort);
      this.approvalResolver = finish;
      this.approval = { id: randomUUID(), tool, summary };
      this.bump({ state: "awaiting_approval", progress: `Claude wants to ${summary}` });
    });
  }

  answerApproval(approvalId: string, approved: boolean): boolean {
    if (!this.approval || this.approval.id !== approvalId || !this.approvalResolver) return false;
    this.approvalResolver(approved);
    return true;
  }

  view(): TurnView {
    return {
      id: this.id,
      version: this.version,
      state: this.state,
      sessionId: this.sessionId,
      project: this.project.name,
      prompt: this.prompt,
      progress: this.progress,
      approval: this.approval,
      reply: this.reply,
      speech: this.reply !== undefined ? toSpeech(this.reply) : undefined,
      error: this.error,
      costUsd: this.costUsd,
    };
  }
}

export class TurnError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

export class TurnManager {
  private turns = new Map<string, Turn>();

  constructor(
    private readonly config: Config,
    private readonly queryFn: QueryFn,
  ) {}

  start(input: { text: string; sessionId?: string; project?: string; cwd?: string }): TurnView {
    const text = input.text?.trim();
    if (!text) throw new TurnError(400, "text is required");

    if (input.sessionId) {
      const busy = [...this.turns.values()].find(
        (t) => t.sessionId === input.sessionId && (t.state === "running" || t.state === "awaiting_approval"),
      );
      if (busy) throw new TurnError(409, "Claude is still working on the last message in this session.");
    }

    const project = this.resolveProject(input.project, input.cwd);
    const turn = new Turn(project, text, input.sessionId);
    this.turns.set(turn.id, turn);
    this.gc();
    void this.run(turn);
    return turn.view();
  }

  get(id: string): Turn | undefined {
    return this.turns.get(id);
  }

  async waitFor(id: string, sinceVersion: number, timeoutMs: number): Promise<TurnView> {
    const turn = this.mustGet(id);
    await turn.waitForChange(sinceVersion, timeoutMs);
    return turn.view();
  }

  answer(id: string, approvalId: string, approved: boolean): TurnView {
    const turn = this.mustGet(id);
    if (!turn.answerApproval(approvalId, approved)) {
      throw new TurnError(409, "That approval is no longer pending.");
    }
    return turn.view();
  }

  cancel(id: string): TurnView {
    const turn = this.mustGet(id);
    if (turn.state === "running" || turn.state === "awaiting_approval") {
      turn.abort.abort();
      turn.bump({ state: "cancelled", progress: "Cancelled" });
    }
    return turn.view();
  }

  private mustGet(id: string): Turn {
    const turn = this.turns.get(id);
    if (!turn) throw new TurnError(404, "Unknown turn");
    return turn;
  }

  private resolveProject(name?: string, cwd?: string): Project {
    if (cwd) {
      const known = this.config.projects.find((p) => p.path === cwd);
      return known ?? { name: cwd.split("/").filter(Boolean).pop() ?? cwd, path: cwd };
    }
    if (name) {
      const found = this.config.projects.find((p) => p.name.toLowerCase() === name.toLowerCase());
      if (!found) throw new TurnError(400, `Unknown project "${name}"`);
      return found;
    }
    return this.config.projects[0];
  }

  private permissionHandler(turn: Turn): CanUseTool {
    const policy = this.config.permissions;
    return async (toolName, input, { signal }): Promise<PermissionResult> => {
      if (READ_ONLY_TOOLS.has(toolName)) return { behavior: "allow", updatedInput: input };
      if (policy === "readonly") {
        return { behavior: "deny", message: "The driver's bridge is read-only. Describe the change instead." };
      }
      if (policy === "acceptEdits" && EDIT_TOOLS.has(toolName)) {
        return { behavior: "allow", updatedInput: input };
      }
      const approved = await turn.requestApproval(toolName, describeTool(toolName, input), signal);
      return approved
        ? { behavior: "allow", updatedInput: input }
        : { behavior: "deny", message: "The driver declined this action. Ask before trying something else." };
    };
  }

  private async run(turn: Turn): Promise<void> {
    const options: Options = {
      cwd: turn.project.path,
      resume: turn.sessionId,
      abortController: turn.abort,
      canUseTool: this.permissionHandler(turn),
      permissionMode: this.config.permissions === "acceptEdits" ? "acceptEdits" : "default",
      systemPrompt: { type: "preset", preset: "claude_code", append: DRIVING_PROMPT },
      settingSources: ["user", "project", "local"],
      env: childEnv(),
      ...(this.config.model ? { model: this.config.model } : {}),
    };

    try {
      for await (const message of this.queryFn({ prompt: turn.prompt, options })) {
        if (turn.state === "cancelled") break;
        this.onMessage(turn, message);
      }
      if (turn.state === "running" || turn.state === "awaiting_approval") {
        turn.bump({ state: "error", error: "Claude Code stopped without a reply." });
      }
    } catch (err) {
      if (turn.state !== "cancelled") {
        turn.bump({ state: "error", error: err instanceof Error ? err.message : String(err) });
      }
    }
  }

  private onMessage(turn: Turn, message: SDKMessage): void {
    switch (message.type) {
      case "system":
        if (message.subtype === "init" && message.session_id && message.session_id !== turn.sessionId) {
          turn.bump({ sessionId: message.session_id });
        }
        break;
      case "assistant": {
        if (message.parent_tool_use_id) break;
        for (const block of message.message.content) {
          if (block.type === "tool_use") {
            turn.bump({ progress: capitalise(describeTool(block.name, block.input as Record<string, unknown>)) });
          }
        }
        break;
      }
      case "result":
        if (message.subtype === "success" && !message.is_error) {
          turn.bump({
            state: "done",
            sessionId: message.session_id,
            reply: message.result,
            progress: "Done",
            costUsd: message.total_cost_usd,
          });
        } else {
          const errors = "errors" in message ? message.errors.join("; ") : "";
          turn.bump({
            state: "error",
            sessionId: message.session_id,
            error: errors || ("result" in message ? message.result : "") || `Claude Code ended with ${message.subtype}`,
            costUsd: message.total_cost_usd,
          });
        }
        break;
      default:
        break;
    }
  }

  private gc() {
    const cutoff = Date.now() - TURN_TTL_MS;
    for (const [id, turn] of this.turns) {
      if (turn.finishedAt && turn.finishedAt < cutoff) this.turns.delete(id);
    }
  }
}

/** The bridge's environment minus session markers it may have inherited from a Claude Code terminal. */
function childEnv(): Record<string, string | undefined> {
  const env: Record<string, string | undefined> = { ...process.env };
  delete env.CLAUDE_CODE_SESSION_ID;
  delete env.CLAUDE_CODE_CHILD_SESSION;
  return env;
}

function capitalise(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
