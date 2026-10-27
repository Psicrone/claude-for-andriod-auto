#!/data/data/com.termux/files/usr/bin/bash
# Sets up the Claude Auto bridge to run on this phone, inside Termux.
#
# Claude Code has no Android build, so it runs in a small Ubuntu userland (proot-distro)
# where its Linux arm64 build works. The bridge listens on 127.0.0.1 only, so the Claude
# Auto app on this phone can reach it and nothing else on the network can.
#
#   curl -fsSL https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/bridge/termux/install.sh | bash
#
# Re-running it updates the bridge.
set -euo pipefail

REPO="${CLAUDE_AUTO_REPO:-https://github.com/Psicrone/claude-for-andriod-auto}"
DISTRO=ubuntu

if [ -z "${PREFIX:-}" ] || [ ! -d "$PREFIX" ]; then
  echo "Run this inside Termux on your phone." >&2
  exit 1
fi

echo "==> Installing Termux packages"
pkg update -y
pkg install -y proot-distro

if ! proot-distro login "$DISTRO" -- true 2>/dev/null; then
  echo "==> Installing Ubuntu (a few hundred MB, one time)"
  proot-distro install "$DISTRO"
fi

echo "==> Installing Node.js, Claude Code and the bridge inside Ubuntu"
proot-distro login "$DISTRO" -- env REPO="$REPO" bash -euo pipefail -c '
  export DEBIAN_FRONTEND=noninteractive
  apt-get update
  apt-get install -y ca-certificates curl git
  if ! command -v node >/dev/null || [ "$(node -p "process.versions.node.split(\".\")[0]")" -lt 20 ]; then
    curl -fsSL https://deb.nodesource.com/setup_22.x | bash -
    apt-get install -y nodejs
  fi
  npm install -g @anthropic-ai/claude-code

  if [ -d ~/claude-auto/.git ]; then
    git -C ~/claude-auto pull --ff-only
  else
    git clone --depth 1 "$REPO" ~/claude-auto
  fi
  cd ~/claude-auto/bridge
  npm ci
  npm run build

  mkdir -p ~/projects
  if [ ! -f .env ]; then
    cat > .env <<ENV
# Loopback only: the Claude Auto app on this phone connects to https://127.0.0.1:8787.
BRIDGE_HOST=127.0.0.1
BRIDGE_PORT=8787
# Folders inside Ubuntu that Claude can work in, as name=path;name=path.
# Clone your repos into ~/projects (run: claude-auto-bridge shell).
BRIDGE_PROJECTS=projects=/root/projects
BRIDGE_PERMISSIONS=ask
ENV
  fi
'

echo "==> Adding the claude-auto-bridge command"
cat > "$PREFIX/bin/claude-auto-bridge" <<'LAUNCHER'
#!/data/data/com.termux/files/usr/bin/bash
# claude-auto-bridge [pair | login | shell | devices ...]
#   (no args)  start the bridge
#   pair       start the bridge and show a pairing code for the app
#   login      sign Claude Code in to your Claude account (one time)
#   shell      open a shell in the Ubuntu environment (clone repos into ~/projects)
#   devices    list paired phones; "devices revoke <id>" removes one
case "${1:-}" in
  login) exec proot-distro login ubuntu -- claude ;;
  shell) exec proot-distro login ubuntu ;;
esac
# Keep the CPU awake while the bridge runs, so replies keep coming with the screen off
# (termux-wake-unlock, or stopping Termux, releases it).
command -v termux-wake-lock >/dev/null && termux-wake-lock
args=""
[ $# -gt 0 ] && args=$(printf ' %q' "$@")
exec proot-distro login ubuntu -- bash -c "cd ~/claude-auto/bridge && exec node dist/server.js$args"
LAUNCHER
chmod 700 "$PREFIX/bin/claude-auto-bridge"

cat <<'DONE'

Done. Next:
  1. claude-auto-bridge login   sign Claude Code in (or put ANTHROPIC_API_KEY in the bridge's .env)
  2. claude-auto-bridge shell   then: cd ~/projects && git clone <your repo> && exit
  3. claude-auto-bridge pair    then in Claude Auto: This phone, enter the code, check the certificate

Also, so Android doesn't stop the bridge:
  - Settings > Apps > Termux > Battery: Unrestricted
  - Android 14+: Developer options > "Disable child process restrictions"
    Android 12-13: see "Run it on this phone" in the README (one adb command)
DONE
