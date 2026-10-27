#!/usr/bin/env bash
# Installs the Claude Auto bridge on macOS or Linux, sets it to start when you log in, and
# shows a QR code to pair your phone.
#
#   curl -fsSL https://raw.githubusercontent.com/Psicrone/claude-for-andriod-auto/main/install/install.sh | bash
#
# No admin rights needed: everything goes in ~/.claude-auto. Run it again to update.
# Options (environment): CLAUDE_AUTO_NO_SERVICE=1 skips start-at-login, CLAUDE_AUTO_NO_PAIR=1
# skips pairing at the end;
# CLAUDE_AUTO_PACKAGE=<file.tgz> installs that bridge package instead of downloading one.
set -euo pipefail

REPO="Psicrone/claude-for-andriod-auto"
HOME_DIR="${CLAUDE_AUTO_HOME:-$HOME/.claude-auto}"
NODE_VERSION="22.20.0"
BIN_DIR="$HOME/.local/bin"

info() { printf '\033[1;38;5;173m==>\033[0m %s\n' "$*"; }
has_tty() { (exec < /dev/tty) 2>/dev/null; }
fail() { printf '\033[1;31mError:\033[0m %s\n' "$*" >&2; exit 1; }

case "$(uname -s)" in
  Darwin) os=darwin ;;
  Linux) os=linux ;;
  *) fail "This installer is for macOS and Linux. On Windows use install.ps1 (see the README)." ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x64 ;;
  arm64 | aarch64) arch=arm64 ;;
  *) fail "Unsupported processor $(uname -m). Claude Code needs a 64-bit Intel/AMD or ARM computer." ;;
esac
command -v curl >/dev/null || fail "curl is needed."
command -v tar >/dev/null || fail "tar is needed."
mkdir -p "$HOME_DIR"

# --- Node.js 20 or newer: the one you have, or a private copy that only the bridge uses.
node_ok() { command -v "$1" >/dev/null && [ "$("$1" -p 'process.versions.node.split(".")[0]')" -ge 20 ]; }
if node_ok node; then
  NODE="$(command -v node)"
  NPM="$(command -v npm)"
else
  NODE_DIR="$HOME_DIR/node"
  if ! node_ok "$NODE_DIR/bin/node" 2>/dev/null; then
    info "Downloading Node.js $NODE_VERSION (used only by the bridge)"
    rm -rf "$NODE_DIR" && mkdir -p "$NODE_DIR"
    curl -fsSL "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-$os-$arch.tar.gz" | tar -xz -C "$NODE_DIR" --strip-components 1
  fi
  NODE="$NODE_DIR/bin/node"
  NPM="$NODE_DIR/bin/npm"
  export PATH="$NODE_DIR/bin:$PATH"
fi

# --- The bridge: the latest release, or built from source if there's no release yet.
APP_DIR="$HOME_DIR/app"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
info "Downloading the bridge"
if [ -n "${CLAUDE_AUTO_PACKAGE:-}" ]; then
  package="$CLAUDE_AUTO_PACKAGE"
elif curl -fsSL -o "$work/bridge.tgz" "https://github.com/$REPO/releases/latest/download/claude-auto-bridge.tgz"; then
  package="$work/bridge.tgz"
else
  info "No release yet, building from the main branch"
  curl -fsSL "https://codeload.github.com/$REPO/tar.gz/refs/heads/main" | tar -xz -C "$work"
  (cd "$work"/*/bridge && "$NPM" ci --no-audit --no-fund --loglevel=error && "$NPM" run build --silent && "$NPM" pack --silent >/dev/null)
  package="$(ls "$work"/*/bridge/claude-auto-bridge-*.tgz)"
fi
info "Installing"
"$NPM" install -g --prefix "$APP_DIR" --no-audit --no-fund --no-update-notifier --loglevel=error "$package" >/dev/null

# A launcher that always uses the Node above, on your PATH.
mkdir -p "$BIN_DIR"
cat > "$BIN_DIR/claude-auto-bridge" <<LAUNCHER
#!/usr/bin/env bash
exec "$NODE" "$APP_DIR/lib/node_modules/claude-auto-bridge/dist/server.js" "\$@"
LAUNCHER
chmod 755 "$BIN_DIR/claude-auto-bridge"
CMD="$BIN_DIR/claude-auto-bridge"

case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *)
    added=""
    for rc in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.profile"; do
      [ -f "$rc" ] || continue
      grep -qs 'Claude Auto bridge' "$rc" || printf '\n# Claude Auto bridge\nexport PATH="%s:$PATH"\n' "$BIN_DIR" >> "$rc"
      added=1
    done
    [ -n "$added" ] || printf '\n# Claude Auto bridge\nexport PATH="%s:$PATH"\n' "$BIN_DIR" >> "$HOME/.profile"
    ;;
esac

# --- Questions (project folders, permissions, Claude sign-in). Read from the terminal, since
# this script itself usually arrives on stdin.
if has_tty; then
  "$CMD" setup < /dev/tty
else
  info "No terminal to ask questions on; run \"claude-auto-bridge setup\" later."
fi

if [ "${CLAUDE_AUTO_NO_SERVICE:-}" != "1" ]; then
  info "Starting the bridge, now and whenever you log in"
  if "$CMD" service install; then
    for _ in $(seq 1 20); do "$CMD" service status | grep -q Running && break; sleep 0.5; done
  else
    info "Couldn't set up start-at-login. Run \"claude-auto-bridge\" yourself when you need it."
  fi
fi

cat <<DONE

$(info "Installed.") Commands (open a new terminal if "claude-auto-bridge" isn't found):
  claude-auto-bridge pair      pair another phone
  claude-auto-bridge setup     change folders or permissions
  claude-auto-bridge devices   list or revoke paired phones

DONE

# --- Pair the phone now. With the service running this asks it for a code; otherwise it
# starts the bridge in this terminal.
if has_tty && [ "${CLAUDE_AUTO_NO_PAIR:-}" != "1" ]; then
  info "Open Claude Auto on your phone, choose My computer, and scan this:"
  "$CMD" pair < /dev/tty || true
fi
