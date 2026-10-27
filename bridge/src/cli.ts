import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, realpathSync, unlinkSync, writeFileSync } from "node:fs";
import { createRequire } from "node:module";
import { homedir, userInfo } from "node:os";
import { basename, join, resolve } from "node:path";
import { createInterface } from "node:readline/promises";
import { defaultDataDir, loadConfig, settingsFile, type Config } from "./config.js";
import { ControlFiles } from "./control.js";
import { DeviceStore } from "./security.js";
import { describePairing, startBridge, VERSION } from "./server.js";

const HELP = `Claude Auto bridge ${VERSION}

Usage: claude-auto-bridge [command]

  (no command)       start the bridge
  pair               show a QR code to pair a phone (works while the bridge runs as a service)
  setup              choose your project folders and how careful Claude should be
  login              sign Claude Code in to your Claude account
  service install    start the bridge automatically when you log in
  service uninstall  stop starting it automatically
  service status     is it running?
  devices            list paired phones; "devices revoke <id>" removes one
  help               this text
`;

export async function main(args: string[]): Promise<void> {
  const [command = "", ...rest] = args;
  switch (command) {
    case "":
    case "start":
      return start(loadConfig(), false);
    case "pair":
    case "--pair":
      return pair(loadConfig());
    case "devices":
      return devicesCommand(loadConfig(), rest);
    case "setup":
      return setup();
    case "login":
      return login(rest);
    case "service":
      return service(loadConfig(), rest[0] ?? "status");
    case "version":
    case "--version":
      console.log(VERSION);
      return;
    case "help":
    case "--help":
    case "-h":
      console.log(HELP);
      return;
    default:
      console.error(`Unknown command "${command}".\n`);
      console.log(HELP);
      process.exitCode = 1;
  }
}

async function start(config: Config, pairNow: boolean) {
  const running = new ControlFiles(config.dataDir).runningBridge();
  if (running) {
    console.log(`The bridge is already running (process ${running.pid}, port ${running.port}).`);
    console.log(`Run "claude-auto-bridge pair" to add a phone.`);
    return;
  }
  await startBridge(config, { pair: pairNow });
}

/** Opens a pairing window: in the running bridge if there is one, otherwise by starting it. */
async function pair(config: Config) {
  const control = new ControlFiles(config.dataDir);
  if (!control.runningBridge()) return start(config, true);

  control.requestPairing();
  const info = await waitFor(() => control.readPairing(), 10_000);
  if (!info) throw new Error("The running bridge didn't answer. Restart it and try again.");
  console.log(await describePairing(info));
  console.log("\n  Waiting for your phone… (Ctrl+C to stop)");

  for (;;) {
    await sleep(1000);
    const now = control.readPairing();
    if (now?.pairedDevice) {
      control.clearPairing();
      console.log(`  Paired "${now.pairedDevice}". You're all set.`);
      return;
    }
    if (!now || now.code !== info.code) {
      console.log('  The code expired. Run "claude-auto-bridge pair" again for a new one.');
      process.exitCode = 1;
      return;
    }
  }
}

function devicesCommand(config: Config, args: string[]) {
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

// ---------------------------------------------------------------------------
// setup: a few questions, written to ~/.claude-auto-bridge/bridge.env.
// ---------------------------------------------------------------------------

const PERMISSION_CHOICES = [
  ["ask", "Ask me by voice before running commands or editing files (recommended)"],
  ["acceptEdits", "Let Claude edit files; ask before running commands"],
  ["readonly", "Read only: Claude can look but not change anything"],
] as const;

async function setup() {
  const dataDir = defaultDataDir();
  const file = settingsFile(dataDir);
  const existing = existsSync(file) ? parseEnvFile(readFileSync(file, "utf8")) : {};
  const rl = prompter();
  try {
    console.log(`\nClaude Auto bridge setup\n`);
    console.log("Which folders should Claude be able to work in from the car?");
    console.log("Give one or more paths separated by commas. Each becomes a \"New chat in <name>\" in the app.");
    const suggested = existing.BRIDGE_PROJECTS
      ? existing.BRIDGE_PROJECTS.split(";").map((p) => p.slice(p.indexOf("=") + 1)).join(", ")
      : suggestProjectFolder();
    const folders = (await rl.ask("Folders", suggested))
      .split(",")
      .map((f) => f.trim())
      .filter(Boolean)
      .map((f) => resolve(f.replace(/^~(?=$|[\\/])/, homedir())));
    for (const folder of folders) {
      if (!existsSync(folder)) console.log(`  Note: ${folder} doesn't exist yet.`);
    }
    const projects = uniqueNames(folders).join(";");

    console.log("\nHow careful should Claude be while you drive?");
    PERMISSION_CHOICES.forEach(([, label], i) => console.log(`  ${i + 1}. ${label}`));
    const current = PERMISSION_CHOICES.findIndex(([value]) => value === existing.BRIDGE_PERMISSIONS);
    const choice = Number(await rl.ask("Choose 1-3", String(current >= 0 ? current + 1 : 1)));
    const permissions = PERMISSION_CHOICES[Math.min(3, Math.max(1, choice || 1)) - 1][0];

    mkdirSync(dataDir, { recursive: true, mode: 0o700 });
    const keep = Object.entries(existing).filter(([k]) => !["BRIDGE_PROJECTS", "BRIDGE_PERMISSIONS"].includes(k));
    const values: Record<string, string> = {
      BRIDGE_HOST: "0.0.0.0",
      BRIDGE_PORT: "8787",
      ...Object.fromEntries(keep),
      BRIDGE_PROJECTS: projects,
      BRIDGE_PERMISSIONS: permissions,
    };
    writeFileSync(
      file,
      [
        "# Claude Auto bridge settings, written by `claude-auto-bridge setup`.",
        "# See https://github.com/Psicrone/claude-for-andriod-auto/blob/main/bridge/.env.example for every option.",
        ...Object.entries(values).map(([k, v]) => `${k}=${v}`),
        "",
      ].join("\n"),
      { mode: 0o600 },
    );
    console.log(`\nSaved to ${file}.`);

    if (!isSignedIn(values) && !process.stdin.isTTY) {
      console.log('Claude Code isn\'t signed in on this computer yet. Run "claude-auto-bridge login".');
    } else if (!isSignedIn(values)) {
      const answer = await rl.ask("\nClaude Code isn't signed in on this computer yet. Sign in now? (Y/n)", "Y");
      rl.close();
      if (!/^n/i.test(answer)) login([]);
    }
  } finally {
    rl.close();
  }
}

/** Questions on stdin. Reads lines through an iterator so piped answers aren't lost. */
function prompter() {
  const rl = createInterface({ input: process.stdin });
  const lines = rl[Symbol.asyncIterator]();
  return {
    async ask(question: string, fallback: string): Promise<string> {
      process.stdout.write(`${question}${fallback ? ` [${fallback}]` : ""}: `);
      const next = await lines.next();
      const answer = next.done ? "" : String(next.value).trim();
      return answer || fallback;
    },
    close: () => rl.close(),
  };
}

function suggestProjectFolder(): string {
  const candidates = ["code", "projects", "src", "dev", "repos", "git", join("Documents", "GitHub"), join("source", "repos")];
  const found = candidates.map((c) => join(homedir(), c)).find((p) => existsSync(p));
  return found ?? process.cwd();
}

/** "name=path" entries, named after each folder, with duplicates numbered. */
export function uniqueNames(folders: string[]): string[] {
  const used = new Map<string, number>();
  return folders.map((folder) => {
    const base = basename(folder) || "home";
    const n = (used.get(base) ?? 0) + 1;
    used.set(base, n);
    return `${n === 1 ? base : `${base}-${n}`}=${folder}`;
  });
}

export function parseEnvFile(text: string): Record<string, string> {
  const values: Record<string, string> = {};
  for (const line of text.split(/\r?\n/)) {
    const match = /^\s*([A-Z0-9_]+)\s*=\s*(.*)$/.exec(line);
    if (match) values[match[1]] = match[2].replace(/^(['"])(.*)\1$/, "$2");
  }
  return values;
}

// ---------------------------------------------------------------------------
// login: the Agent SDK ships its own Claude Code binary, so a separate install
// isn't needed. Running it interactively signs it in; the bridge then uses
// the same login.
// ---------------------------------------------------------------------------

function isSignedIn(settings: Record<string, string>): boolean {
  if (process.env.ANTHROPIC_API_KEY || process.env.CLAUDE_CODE_OAUTH_TOKEN) return true;
  if (settings.ANTHROPIC_API_KEY || settings.CLAUDE_CODE_OAUTH_TOKEN) return true;
  const configDir = process.env.CLAUDE_CONFIG_DIR;
  const files = configDir ? [join(configDir, ".claude.json")] : [join(homedir(), ".claude.json")];
  return files.some((f) => {
    try {
      return Boolean(JSON.parse(readFileSync(f, "utf8")).oauthAccount);
    } catch {
      return false;
    }
  });
}

export function bundledClaude(): string | undefined {
  const require = createRequire(import.meta.url);
  const ext = process.platform === "win32" ? ".exe" : "";
  const base = `@anthropic-ai/claude-agent-sdk-${process.platform}-${process.arch}`;
  for (const pkg of process.platform === "linux" ? [base, `${base}-musl`] : [base]) {
    try {
      const path = require.resolve(`${pkg}/claude${ext}`);
      if (existsSync(path)) return path;
    } catch {
      // Not installed for this platform.
    }
  }
  return undefined;
}

function login(args: string[]) {
  const claude = bundledClaude() ?? "claude";
  if (args.length === 0) {
    console.log("\nClaude Code will open. Choose to sign in with your Claude account, follow the link,");
    console.log("then type /exit once it says you're logged in.\n");
  }
  const result = spawnSync(claude, args, { stdio: "inherit", shell: claude === "claude" && process.platform === "win32" });
  if (result.error) throw new Error(`Couldn't start Claude Code: ${result.error.message}`);
}

// ---------------------------------------------------------------------------
// service: start at login. systemd (Linux), launchd (macOS) or a scheduled
// task (Windows), all per user, no admin rights needed.
// ---------------------------------------------------------------------------

const SERVICE_NAME = "claude-auto-bridge";
const MAC_LABEL = "com.claudeauto.bridge";
const WINDOWS_TASK = "Claude Auto bridge";

function service(config: Config, action: string) {
  const node = process.execPath;
  const script = realpathSync(process.argv[1]);
  const log = join(config.dataDir, "bridge.log");
  mkdirSync(config.dataDir, { recursive: true, mode: 0o700 });

  if (action === "status") {
    const running = new ControlFiles(config.dataDir).runningBridge();
    console.log(running ? `Running (process ${running.pid}, port ${running.port}).` : "Not running.");
    return;
  }
  if (action !== "install" && action !== "uninstall") throw new Error(`Unknown service action "${action}".`);
  const install = action === "install";

  if (process.platform === "linux") {
    const unitDir = join(homedir(), ".config", "systemd", "user");
    const unit = join(unitDir, `${SERVICE_NAME}.service`);
    if (install) {
      mkdirSync(unitDir, { recursive: true });
      writeFileSync(
        unit,
        [
          "[Unit]",
          "Description=Claude Auto bridge",
          "After=network-online.target",
          "",
          "[Service]",
          `ExecStart="${node}" "${script}"`,
          `Environment="PATH=${process.env.PATH ?? "/usr/bin:/bin"}"`,
          "Restart=on-failure",
          "RestartSec=5",
          "",
          "[Install]",
          "WantedBy=default.target",
          "",
        ].join("\n"),
      );
      run("systemctl", ["--user", "daemon-reload"]);
      run("systemctl", ["--user", "enable", "--now", SERVICE_NAME]);
      run("systemctl", ["--user", "restart", SERVICE_NAME]);
      // Keep it running when you're not logged in at the desktop (allowed for your own user on most distros).
      run("loginctl", ["enable-linger", userInfo().username], true);
      console.log(`Installed. Logs: journalctl --user -u ${SERVICE_NAME} -f`);
    } else {
      run("systemctl", ["--user", "disable", "--now", SERVICE_NAME], true);
      removeFile(unit);
      run("systemctl", ["--user", "daemon-reload"], true);
      console.log("Removed.");
    }
  } else if (process.platform === "darwin") {
    const plist = join(homedir(), "Library", "LaunchAgents", `${MAC_LABEL}.plist`);
    const domain = `gui/${userInfo().uid}`;
    run("launchctl", ["bootout", domain, plist], true);
    if (install) {
      mkdirSync(join(homedir(), "Library", "LaunchAgents"), { recursive: true });
      writeFileSync(plist, macPlist(node, script, log));
      run("launchctl", ["bootstrap", domain, plist]);
      console.log(`Installed. Logs: ${log}`);
    } else {
      removeFile(plist);
      console.log("Removed.");
    }
  } else if (process.platform === "win32") {
    if (install) {
      // A hidden window: wscript runs a .cmd without a console popping up at every login.
      const cmd = join(config.dataDir, "run-bridge.cmd");
      const vbs = join(config.dataDir, "run-bridge-hidden.vbs");
      writeFileSync(cmd, `@echo off\r\n"${node}" "${script}" >> "${log}" 2>&1\r\n`);
      writeFileSync(vbs, `CreateObject("WScript.Shell").Run """${cmd}""", 0, False\r\n`);
      run("schtasks", ["/Create", "/F", "/SC", "ONLOGON", "/RL", "LIMITED", "/TN", WINDOWS_TASK, "/TR", `wscript.exe "${vbs}"`]);
      run("schtasks", ["/Run", "/TN", WINDOWS_TASK]);
      console.log(`Installed. Logs: ${log}`);
      console.log("If Windows asks whether Node.js may use the network, allow it on private networks.");
    } else {
      run("schtasks", ["/End", "/TN", WINDOWS_TASK], true);
      run("schtasks", ["/Delete", "/F", "/TN", WINDOWS_TASK], true);
      console.log("Removed.");
    }
  } else {
    throw new Error(`Starting at login isn't supported on ${process.platform}. Run "claude-auto-bridge" yourself.`);
  }
}

function macPlist(node: string, script: string, log: string): string {
  const esc = (s: string) => s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>${MAC_LABEL}</string>
  <key>ProgramArguments</key>
  <array><string>${esc(node)}</string><string>${esc(script)}</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>${esc(process.env.PATH ?? "/usr/bin:/bin")}</string></dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardOutPath</key><string>${esc(log)}</string>
  <key>StandardErrorPath</key><string>${esc(log)}</string>
</dict>
</plist>
`;
}

function run(command: string, args: string[], ignoreFailure = false) {
  const result = spawnSync(command, args, { stdio: ignoreFailure ? "ignore" : "inherit" });
  if (!ignoreFailure && (result.error || result.status !== 0)) {
    throw new Error(`${command} ${args.join(" ")} failed${result.error ? `: ${result.error.message}` : ""}`);
  }
}

function removeFile(path: string) {
  if (existsSync(path)) unlinkSync(path);
}

async function waitFor<T>(read: () => T | undefined, timeoutMs: number): Promise<T | undefined> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const value = read();
    if (value) return value;
    await sleep(250);
  }
  return undefined;
}

function sleep(ms: number) {
  return new Promise((r) => setTimeout(r, ms));
}
