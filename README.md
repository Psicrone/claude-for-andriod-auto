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

It works in three modes, and you can switch between them in the app:

- **My computer**: Claude Code runs on your PC, with your repos and settings, through a small bridge (below). This mode runs on your Claude Code login.
- **This phone**: the same bridge and Claude Code run on the phone itself, inside Termux. No computer and no API key needed, but the phone does the work and needs to stay charged. See [Run it on this phone](#run-it-on-this-phone).
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

### 1. Install the app

Download `claude-auto.apk` from the [latest release](https://github.com/Psicrone/claude-for-andriod-auto/releases/latest) on your phone and open it (allow installs from your browser when asked). Builds of every change are also on the Actions tab as `claude-auto-debug-apk`.

Open **Claude Auto** and tap **Get started**. The setup screens ask where Claude should run and walk you through the rest:

- **My computer or Raspberry Pi**: shows the install command for your computer, then you scan the QR code it prints.
- **This phone**: installs Termux, copies one command that sets everything up, and pairs by itself when it finishes.
- **Cloud**: asks for an API key and a GitHub token, with links to create both.

Then it asks for the microphone and notifications, and shows how to switch on Android Auto. Change any of this later with the **Connection** button at the top of the main screen.

### 2. Install the bridge on your computer

Claude Code doesn't need to be installed separately (the bridge brings its own copy), and no admin rights are needed. One command installs the bridge, asks which folders Claude may work in and how careful it should be, signs Claude in if it isn't already, starts the bridge now and whenever you log in, and shows the pairing QR code.

**Windows**, in PowerShell:

```powershell
irm https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/install/install.ps1 | iex
```

**macOS or Linux**, in a terminal:

```bash
curl -fsSL https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/install/install.sh | bash
```

Run it again to update. It uses your Node.js if it's version 20 or newer, and otherwise downloads a private copy for the bridge. Afterwards:

| Command | What it does |
| --- | --- |
| `claude-auto-bridge pair` | Show a QR code to pair a phone (also while the bridge runs in the background) |
| `claude-auto-bridge setup` | Change project folders or the permission policy |
| `claude-auto-bridge login` | Sign Claude Code in to your Claude account |
| `claude-auto-bridge devices` | List paired phones; `devices revoke <id>` cuts one off |
| `claude-auto-bridge service install` / `uninstall` | Start (or stop starting) the bridge when you log in |

Settings live in `~/.claude-auto-bridge/bridge.env` (see [`bridge/.env.example`](bridge/.env.example) for every option). The permission policies are `ask` (default: anything beyond reading files is read out for your approval), `acceptEdits` (file edits are allowed, commands still need a yes) and `readonly`.

To run it from a clone instead: `cd bridge && npm install && npm run build && npm start`.

### 2b. Or on a Raspberry Pi (or any Docker host)

A Pi 4 or 5 running 64-bit Raspberry Pi OS makes an always-on bridge that doesn't need your computer awake. On the Pi:

```bash
curl -fsSL https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/install/raspberry-pi.sh | bash
```

It installs Docker if needed, runs the bridge image (`ghcr.io/psicrone/claude-auto-bridge`, built for arm64 and amd64) so it starts on every boot, signs Claude in (open the link it shows on any device) and shows the pairing QR code. Clone the repos you want into `~/projects`. Then use `claude-auto pair`, `claude-auto login`, `claude-auto logs`, `claude-auto update` or `claude-auto shell` (set up git credentials there so Claude can push). Settings are in `~/claude-auto/bridge.env`.

Plain Docker works too:

```bash
docker run -d --name claude-auto --network host --restart unless-stopped \
  -v claude-auto:/home/node -v ~/projects:/home/node/projects \
  ghcr.io/psicrone/claude-auto-bridge
docker exec -it claude-auto claude-auto-bridge login
docker exec -it claude-auto claude-auto-bridge pair
```

### 3. Pair your phone

In the app choose **My computer or Raspberry Pi**, tap **Scan the QR code** and point it at the code in the terminal. You can also scan it with the phone's camera app, which opens Claude Auto. The QR code carries the bridge's address, a one-time code and the bridge certificate's fingerprint, so the app pins the right certificate without you comparing anything. If you'd rather type, tap **Enter the code by hand**; the app then shows the certificate's short code to compare with the `Certificate:` line in the terminal.

The address in the QR code is the one most likely to work from the car: a Tailscale address if the computer has one, then the home network. Set `BRIDGE_PUBLIC_URL` to override it.

How the connection is protected:

- **Everything is encrypted.** The bridge only speaks HTTPS (TLS 1.2+). Its certificate is generated on your computer on first run and never leaves it.
- **Only your phone gets in.** The app pins that exact certificate, so it won't talk to anything impersonating your computer, even with a valid certificate from a public authority. The bridge accepts only paired phones: each phone gets its own random 256-bit token, and the bridge stores only a hash of it.
- **Pairing codes are one-time.** A code expires after 10 minutes or 5 wrong tries, and works for one phone. After 10 failed attempts from one address, that address is locked out for 15 minutes.
- **You can revoke a phone.** `claude-auto-bridge devices revoke <id>` cuts one off immediately.
- **No plain HTTP.** The app refuses `http://` addresses.

To reach the bridge away from home, use [Tailscale](https://tailscale.com) on the computer and the phone. That adds a second, independent encryption layer and keeps the port off the public internet. If you'd rather use `tailscale serve` or a Cloudflare tunnel, set `BRIDGE_HOST=127.0.0.1`, `BRIDGE_TLS=off` and `BRIDGE_PUBLIC_URL` to the proxy's `https://` URL. The proxy's certificate is publicly trusted, so the app checks it normally instead of pinning it, and pairing and device tokens work the same way.

### 4. Allow it in Android Auto

Apps installed outside the Play Store only appear in Android Auto after you enable developer mode (the last setup screen has a **Show me** button for this):

1. Android Auto settings on the phone → tap **Version** ten times → allow developer settings.
2. Overflow menu → **Developer settings** → enable **Unknown sources**.
3. Back in Android Auto settings → **Customize launcher** → make sure Claude Auto is ticked.

You can try everything without a car using the [Desktop Head Unit](https://developer.android.com/training/cars/testing/dhu).

## Cloud mode

1. Create an API key at [platform.claude.com](https://platform.claude.com) (Settings, API keys). Cloud mode bills that key per use, separately from any Claude subscription.
2. Create a [fine-grained GitHub token](https://github.com/settings/personal-access-tokens/new) limited to the repos you want, with **Contents: read and write**. It's handed to Anthropic's git proxy and is never placed inside the sandbox, so code running there can't read it.
3. In the app's setup (or **Connection** later), choose **Cloud**, enter the key, the token and your repos (`owner/repo`, one per line), and tap **Connect**.

On first use the app creates one agent ("Claude Auto", Claude Opus 5.5) and one cloud environment in your Anthropic account, then reuses them. You'll see "New chat in *repo*" for each repo plus your recent cloud sessions, both on the phone and in the car.

How it behaves:

- Claude works in `/workspace/<repo>`. When it changes code it commits to a new `claude/...` branch, pushes it, and tells you the branch name. It never pushes to `main`. Open the pull request yourself when you're back at a screen.
- Tool permissions use the server's **auto** policy. Safe actions just run, high-risk ones are refused, and anything the server can't decide pauses and is read out to you for a spoken yes or no.
- Web search and web fetch are off. You can turn them on for the "Claude Auto" agent in the Claude Console if you want them.
- You can inspect every session (transcript, tool calls, cost) in the Claude Console under **Managed Agents → Sessions**.
- The API key and GitHub token are stored in the app's private storage on your phone. Use a dedicated key with a spend limit set in the Console.

## Run it on this phone

Claude Code can run on the phone in [Termux](https://termux.dev). Claude Code has no Android build, so the installer puts it, Node.js and the bridge in a small Ubuntu environment ([proot-distro](https://github.com/termux/proot-distro)) where its Linux arm64 build runs. The bridge listens on `127.0.0.1` only, so only apps on the phone can reach it, and it still uses HTTPS and pairing, so other apps on the phone can't use it either.

You need an arm64 phone running Android 10 or newer with about 1.5 GB free.

1. Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/) or its [GitHub releases](https://github.com/termux/termux-app/releases). The Play Store version is out of date.
2. In Claude Auto's setup choose **This phone** and tap **Copy and open Termux**. In Termux, long-press, **Paste** and press Enter. That runs:

   ```sh
   curl -fsSL https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/bridge/termux/install.sh | bash && claude-auto-bridge login && claude-auto-bridge pair
   ```

   It installs Node.js and the bridge (with Claude Code) in Ubuntu, then signs Claude Code in to your Claude account (it prints a link to open in the browser; type `/exit` when it says you're logged in). Finally `claude-auto-bridge pair` opens Claude Auto with the pairing details, so there's nothing to type. You can instead put `ANTHROPIC_API_KEY=...` in `~/claude-auto/bridge/.env` inside Ubuntu.
3. `claude-auto-bridge shell` opens the Ubuntu environment. Clone the repos you want into `~/projects`, then `exit`. To list projects separately, edit `BRIDGE_PROJECTS` in `~/claude-auto/bridge/.env`.

After pairing, start the bridge with `claude-auto-bridge` and leave Termux running in the background. Run the install command again later to update. The **This phone** and **My computer** pairings are kept separately, so you can switch between them.

Android stops background apps aggressively, so do these once:

- **Battery**: Settings → Apps → Termux → Battery → **Unrestricted**.
- **Wake lock**: the launcher runs `termux-wake-lock`, so the phone keeps working with the screen off. Termux shows a notification while it's held; `termux-wake-unlock` releases it.
- **Child process limit** (Android 12 and newer kills Termux's background processes otherwise):
  - Android 14 or newer: enable Developer options, then turn on **Disable child process restrictions**.
  - Android 12 or 13: from a computer with `adb`, run
    `adb shell "/system/bin/device_config set_sync_disabled_for_tests persistent; /system/bin/device_config put activity_manager max_phantom_processes 2147483647"`.

Claude Code works the phone hard while it's busy. Keep it on a charger in the car.

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

CI runs both test suites, runs the installers on Windows, macOS and Linux, builds the Docker image for arm64 and amd64 (pushed to GHCR from `main`), and publishes the debug APK as an artifact.

### Releases

Push a tag such as `v0.3.0` and the Release workflow publishes `claude-auto.apk`, the `claude-auto-bridge.tgz` package that the installers download, and the install scripts. CI pushes the matching Docker tag.

Android only installs an update over an APK signed with the same key, so give the workflow a permanent key once. On any computer with Java:

```bash
keytool -genkeypair -v -keystore claude-auto.jks -alias claude-auto -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 claude-auto.jks   # macOS: base64 -i claude-auto.jks
```

Add repository secrets (Settings → Secrets and variables → Actions): `CLAUDE_AUTO_KEYSTORE_BASE64` (the base64 output), `CLAUDE_AUTO_KEYSTORE_PASSWORD`, `CLAUDE_AUTO_KEY_ALIAS` (`claude-auto`) and `CLAUDE_AUTO_KEY_PASSWORD`. Keep `claude-auto.jks` somewhere safe. Without the secrets the release is signed with a throwaway key.

The first push of the Docker image creates the `claude-auto-bridge` package on GHCR. If `docker pull` asks for a login, make the package public under your GitHub profile → Packages → claude-auto-bridge → Package settings.

## Notes and limits

- **Play Store.** Google reviews Android Auto apps by category. This app uses the messaging category and the standard messaging templates and notifications, which keeps it within driver-distraction rules, but a public Play listing for an AI assistant isn't guaranteed. Sideloading as above works for personal use.
- **Car API level.** The in-car conversation list needs Android Auto with Car App API level 7 or later (any reasonably current Android Auto). Message notifications with voice reply work on all versions.
- **Turns survive the app, not the bridge.** If the bridge restarts mid-turn the app tells you it lost track of the request; the Claude Code session itself is still there to resume.
- **Cost.** Each turn uses your Claude Code account as if you'd typed it at the terminal.
