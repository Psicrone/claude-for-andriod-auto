#!/usr/bin/env bash
# Runs the Claude Auto bridge in Docker on a Raspberry Pi (or any 64-bit Linux box), so it's
# always on without keeping a computer awake. Installs Docker if needed, starts the bridge,
# signs Claude Code in and shows a QR code to pair your phone.
#
#   curl -fsSL https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/install/raspberry-pi.sh | bash
#
# Needs a 64-bit OS (Raspberry Pi OS 64-bit on a Pi 4 or 5). Run it again to update.
set -euo pipefail

IMAGE="${CLAUDE_AUTO_IMAGE:-ghcr.io/psicrone/claude-auto-bridge:latest}"
DIR="${CLAUDE_AUTO_DIR:-$HOME/claude-auto}"
PROJECTS="${CLAUDE_AUTO_PROJECTS:-$HOME/projects}"

info() { printf '\033[1;38;5;173m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }
has_tty() { (exec < /dev/tty) 2>/dev/null; }

[ "$(uname -s)" = Linux ] || fail "This script is for Linux (Raspberry Pi OS, Ubuntu, Debian…)."
case "$(uname -m)" in
  aarch64 | arm64 | x86_64 | amd64) ;;
  armv7l | armv6l) fail "Claude Code needs a 64-bit OS. Install Raspberry Pi OS (64-bit) with Raspberry Pi Imager, then run this again." ;;
  *) fail "Unsupported processor $(uname -m)." ;;
esac

# --- Docker
if ! command -v docker >/dev/null; then
  info "Installing Docker"
  curl -fsSL https://get.docker.com | sh
  sudo usermod -aG docker "$USER" || true
fi
if docker info >/dev/null 2>&1; then DOCKER=(docker); else DOCKER=(sudo docker); fi
"${DOCKER[@]}" compose version >/dev/null 2>&1 || fail "Docker Compose is missing. Install the docker-compose-plugin package and run this again."

# --- Files: compose.yaml, settings and a small helper command.
mkdir -p "$DIR" "$PROJECTS"
cat > "$DIR/compose.yaml" <<YAML
# Claude Auto bridge. Manage it with the claude-auto command (claude-auto help).
services:
  bridge:
    image: $IMAGE
    container_name: claude-auto
    # Host networking: the bridge sees this machine's real addresses (home network, Tailscale)
    # and puts the right one in the pairing QR code.
    network_mode: host
    restart: unless-stopped
    env_file: bridge.env
    volumes:
      # Claude's login, the bridge's certificate and paired phones.
      - home:/home/node
      # Your repos. Clone them here; each conversation can work in any of them.
      - $PROJECTS:/home/node/projects
volumes:
  home:
YAML

if [ ! -f "$DIR/bridge.env" ]; then
  cat > "$DIR/bridge.env" <<'ENV'
# Claude Auto bridge settings. After editing: claude-auto restart
# Folders (inside the container) as name=path;name=path. /home/node/projects is your projects folder.
BRIDGE_PROJECTS=projects=/home/node/projects
# ask (approve risky steps by voice), acceptEdits (edits allowed, commands asked) or readonly.
BRIDGE_PERMISSIONS=ask
BRIDGE_PORT=8787
# Instead of signing in with "claude-auto login" you can use an API key:
# ANTHROPIC_API_KEY=sk-ant-...
ENV
fi

mkdir -p "$HOME/.local/bin"
cat > "$HOME/.local/bin/claude-auto" <<HELPER
#!/usr/bin/env bash
# claude-auto pair | login | logs | restart | update | shell | devices | stop | help
set -e
cd "$DIR"
if docker info >/dev/null 2>&1; then d=(docker); else d=(sudo docker); fi
case "\${1:-help}" in
  pair)    exec "\${d[@]}" exec -it claude-auto claude-auto-bridge pair ;;
  login)   exec "\${d[@]}" exec -it claude-auto claude-auto-bridge login ;;
  devices) shift; exec "\${d[@]}" exec claude-auto claude-auto-bridge devices "\$@" ;;
  logs)    exec "\${d[@]}" compose logs -f --tail 100 ;;
  restart) exec "\${d[@]}" compose up -d --force-recreate ;;
  update)  "\${d[@]}" compose pull && exec "\${d[@]}" compose up -d ;;
  shell)   exec "\${d[@]}" exec -it claude-auto bash ;;
  stop)    exec "\${d[@]}" compose down ;;
  *) echo "claude-auto pair | login | logs | restart | update | shell | devices [revoke <id>] | stop" ;;
esac
HELPER
chmod 755 "$HOME/.local/bin/claude-auto"
case ":$PATH:" in
  *":$HOME/.local/bin:"*) ;;
  *) grep -qs '.local/bin' "$HOME/.profile" || printf '\nexport PATH="$HOME/.local/bin:$PATH"\n' >> "$HOME/.profile" ;;
esac

# --- Start it
info "Downloading and starting the bridge"
(cd "$DIR" && "${DOCKER[@]}" compose pull && "${DOCKER[@]}" compose up -d)
for _ in $(seq 1 30); do
  "${DOCKER[@]}" exec claude-auto claude-auto-bridge service status 2>/dev/null | grep -q Running && break
  sleep 1
done

if has_tty; then
  signed_in=$("${DOCKER[@]}" exec claude-auto node -e '
    const f = require("fs"); let ok = !!process.env.ANTHROPIC_API_KEY;
    try { ok = ok || !!JSON.parse(f.readFileSync("/home/node/.claude.json", "utf8")).oauthAccount } catch {}
    console.log(ok ? "yes" : "no")' 2>/dev/null || echo no)
  if [ "$signed_in" != yes ]; then
    info "Sign Claude Code in to your Claude account (open the link it shows on any device, then type /exit)"
    "${DOCKER[@]}" exec -it claude-auto claude-auto-bridge login < /dev/tty || true
  fi
  info "Open Claude Auto on your phone, choose My computer, and scan this:"
  "${DOCKER[@]}" exec -it claude-auto claude-auto-bridge pair < /dev/tty || true
fi

cat <<DONE

$(info "Done.") The bridge starts by itself whenever the Pi boots.
  Put your repos in $PROJECTS (git clone …) so Claude can work on them.
  claude-auto pair      pair another phone       claude-auto logs     see what it's doing
  claude-auto login     sign in again             claude-auto update   get the latest version
  claude-auto shell     a shell inside (set up git credentials here so Claude can push)
Open a new terminal if "claude-auto" isn't found.
DONE
