# Claude Auto

Talk to your Claude Code sessions from Android Auto. Dictate a task on the way to work, hear Claude's answer read out, and approve or refuse risky actions by saying "yes" or "no".

```
 Car display / mic / speakers
        │  Android Auto (templated messaging + message notifications)
        ▼
 Claude Auto app on your phone ──HTTPS, pinned cert──▶ claude-auto bridge on your computer
                                                         │  Claude Agent SDK
                                                         ▼
                                                    Claude Code sessions in your projects
```

It works in two modes, and you can switch between them in the app:

- **My computer**: Claude Code runs on your PC, with your repos and settings, through a small bridge (below). This mode runs on your Claude Code login.
- **Cloud**: no computer needed. The app talks directly to [Anthropic-hosted agents](https://platform.claude.com/docs/en/managed-agents/overview). Each conversation gets its own cloud sandbox with your GitHub repo cloned in, where Claude can edit, test, commit and push. This mode bills an Anthropic API key. See [Cloud mode](#cloud-mode).

The code has three parts:

| Part | What it does |
| --- | --- |
| [`bridge/`](bridge) | A small Node server that runs next to Claude Code on your computer. It starts and resumes Claude Code sessions with the [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk), turns Claude's replies into speakable text, and holds risky tool calls until you approve them by voice. |
| [`android/core`](android/core) | Plain Kotlin shared by the app: the backend interface, the cloud backend (Anthropic Java SDK), spoken-answer parsing and speech formatting. Tested on the JVM against a fake Managed Agents API. |
| [`android/`](android) | The phone app. On the car screen it's a templated messaging app: each Claude Code session is a conversation that Android Auto reads aloud and you reply to by voice. Replies also arrive as car message notifications. On the phone it has a push-to-talk screen with speech recognition and text-to-speech. |

## Why a bridge?

Claude Code sessions live on the machine where Claude Code runs, with your repositories, credentials and settings. Rather than copying any of that to the phone, the phone sends what you said to the bridge, and the bridge drives Claude Code there with the official Agent SDK. Your sessions show up in both places: anything you start from the car can be picked up later with `claude --resume` at your desk, and vice versa.

## How it feels in the car

1. Open **Claude Auto** from the Android Auto launcher. You'll see "New chat in *project*" for each project plus your most recent Claude Code sessions.
2. Tap a conversation. Android Auto reads the latest messages and offers **Reply**. Say what you want: *"In the work app, find out why the login test is flaky and fix it."*
3. Claude works. When it wants to run a command or edit a file you'll get a message notification: *"I'd like to run the command npm test. Say yes to allow it, or no to skip it."* Reply "yes" or "no".
4. When it's done you hear a two to four sentence summary. Code and long output stay on your computer; the spoken version tells you when there's more to read later.

Say "stop" while Claude is working to cancel the current request.

## Setup

### 1. Run the bridge on your computer

Requirements: Node 20 or newer, and Claude Code signed in on that machine (`claude` works in a terminal).

```bash
cd bridge
npm install
npm run build
cp .env.example .env    # then edit it
npm start
```

Edit `.env`:

- `BRIDGE_PROJECTS`: the folders you want to talk about, as `name=path` pairs separated by `;`. The first is the default for new chats.
- `BRIDGE_HOST`: the address the phone will use, such as your Tailscale IP (`100.x.y.z`), or `0.0.0.0` for all interfaces.
- `BRIDGE_PERMISSIONS`: `ask` (default: anything beyond reading files is read out for approval), `acceptEdits` (file edits are allowed, commands still need a yes), or `readonly`.

Run the bridge from a normal terminal (not from inside a Claude Code session) so the sessions it creates are independent. On first start it prints something like:

```
Claude Auto bridge 0.2.0
  Listening on https://100.101.102.103:8787
  Certificate: 952F-A23C  (SHA-256 95:2F:A2:3C:…)
  Pairing code: LKPS-JP8N   (valid for 10 minutes, one phone)
```

### 2. Pair your phone

Install the app (step 3), open it, enter the bridge URL and the pairing code, and tap **Pair with computer**. The app shows the bridge's certificate code; tap **They match** only if it's the same as the `Certificate:` line in your terminal. From then on:

- **Everything is encrypted.** The bridge only speaks HTTPS (TLS 1.2+). Its certificate is generated on your computer on first run and never leaves it.
- **Only your phone gets in.** The app pins that exact certificate, so it won't talk to anything impersonating your computer, even with a valid certificate from a public authority. The bridge accepts only paired phones: each phone gets its own random 256-bit token, and the bridge stores only a hash of it.
- **Pairing codes are one-time.** A code expires after 10 minutes or 5 wrong tries, and works for one phone. After 10 failed attempts from one address, that address is locked out for 15 minutes.
- **You can revoke a phone.** `npm run devices` lists paired phones, and `npm run devices -- revoke <id>` cuts one off immediately. `npm run pair` starts the bridge with a fresh code to add another phone.
- **No plain HTTP.** The app refuses `http://` addresses.

To reach the bridge away from home, use [Tailscale](https://tailscale.com) on the computer and the phone and give the app the Tailscale address. That adds a second, independent encryption layer and keeps the port off the public internet. If you'd rather use `tailscale serve` or a Cloudflare tunnel, set `BRIDGE_HOST=127.0.0.1` and `BRIDGE_TLS=off`, and point the app at the proxy's `https://` URL. The proxy's certificate is publicly trusted, so the app checks it normally instead of pinning it, and pairing and device tokens work the same way.

### 3. Install the app

Download `claude-auto-debug-apk` from the latest CI run (Actions tab), or build it yourself with `cd android && ./gradlew assembleDebug` and install `app/build/outputs/apk/debug/app-debug.apk`. Allow microphone and notification access when asked. Long-press the title later to pair again.

### 4. Allow it in Android Auto

Apps installed outside the Play Store only appear in Android Auto after you enable developer mode:

1. Android Auto settings on the phone → tap **Version** ten times → allow developer settings.
2. Overflow menu → **Developer settings** → enable **Unknown sources**.
3. Back in Android Auto settings → **Customize launcher** → make sure Claude Auto is ticked.

You can try everything without a car using the [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu).

## Cloud mode

1. Create an API key at [platform.claude.com](https://platform.claude.com) (Settings, API keys). Cloud mode bills that key per use, separately from any Claude subscription.
2. Create a [fine-grained GitHub token](https://github.com/settings/personal-access-tokens/new) limited to the repos you want, with **Contents: read and write**. It's handed to Anthropic's git proxy and is never placed inside the sandbox, so code running there can't read it.
3. In the app, long-press the title, choose **Cloud**, enter the key, the token and your repos (`owner/repo`, one per line), and tap **Save and connect**.

On first use the app creates one agent ("Claude Auto", Claude Opus 5.5) and one cloud environment in your Anthropic account, then reuses them. You'll see "New chat in *repo*" for each repo plus your recent cloud sessions, both on the phone and in the car.

How it behaves:

- Claude works in `/workspace/<repo>`. When it changes code it commits to a new `claude/...` branch, pushes it, and tells you the branch name. It never pushes to `main`. Open the pull request yourself when you're back at a screen.
- Tool permissions use the server's **auto** policy. Safe actions just run, high-risk ones are refused, and anything the server can't decide pauses and is read out to you for a spoken yes or no.
- Web search and web fetch are off. You can turn them on for the "Claude Auto" agent in the Claude Console if you want them.
- You can inspect every session (transcript, tool calls, cost) in the Claude Console under **Managed Agents → Sessions**.
- The API key and GitHub token are stored in the app's private storage on your phone. Use a dedicated key with a spend limit set in the Console.

## Bridge API

HTTPS only. All endpoints except `/health` and `/pair` need `Authorization: Bearer <device token>`.

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Liveness and version |
| `POST` | `/pair` | `{ code, deviceName }`: trade the one-time pairing code for this device's token |
| `GET` | `/projects` | Configured projects |
| `GET` | `/sessions?limit=10` | Recent Claude Code sessions across those projects |
| `POST` | `/turns` | `{ text, sessionId?, project? }`: send a message (new session when `sessionId` is omitted) |
| `GET` | `/turns/:id?since=<version>&wait=<seconds>` | Long-poll a turn: progress, pending approval, final `reply` and `speech` |
| `POST` | `/turns/:id/approval` | `{ approvalId, approve }`: answer a pending tool approval |
| `POST` | `/turns/:id/cancel` | Stop the turn |

## Development

```bash
cd bridge && npm test                          # bridge unit and HTTP tests
cd android && ./gradlew :core:test testDebugUnitTest   # core (incl. cloud backend) and app tests
```

CI builds both and publishes the debug APK as an artifact.

## Notes and limits

- **Play Store.** Google reviews Android Auto apps by category. This app uses the messaging category and the standard messaging templates and notifications, which keeps it within driver-distraction rules, but a public Play listing for an AI assistant isn't guaranteed. Sideloading as above works for personal use.
- **Car API level.** The in-car conversation list needs Android Auto with Car App API level 7 or later (any reasonably current Android Auto). Message notifications with voice reply work on all versions.
- **Turns survive the app, not the bridge.** If the bridge restarts mid-turn the app tells you it lost track of the request; the Claude Code session itself is still there to resume.
- **Cost.** Each turn uses your Claude Code account as if you'd typed it at the terminal.
