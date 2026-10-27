#!/usr/bin/env bash
# Makes the Google Play upload key and stores it as GitHub Actions secrets, so the Release and
# Google Play workflows sign with it. Run once, on your own computer, from the repository folder.
# Needs: keytool (comes with any JDK) and the GitHub CLI, signed in (gh auth login).
#
# Keep the claude-auto-upload.jks file and its password somewhere safe (a password manager).
# With Play App Signing, Google keeps the key users' phones see; if this upload key is ever lost,
# Play Console can reset it (Setup > App signing > Request upload key reset).
set -euo pipefail

KEYSTORE="${1:-claude-auto-upload.jks}"
ALIAS=claude-auto-upload

command -v keytool >/dev/null || { echo "keytool not found: install a JDK (e.g. Temurin 17)." >&2; exit 1; }
command -v gh >/dev/null || { echo "gh not found: install the GitHub CLI and run gh auth login." >&2; exit 1; }
[ -e "$KEYSTORE" ] && { echo "$KEYSTORE already exists; not overwriting it." >&2; exit 1; }

read -rsp "Choose a password for the upload key (6+ characters): " PASSWORD; echo
read -rsp "Type it again: " AGAIN; echo
[ "$PASSWORD" = "$AGAIN" ] || { echo "The passwords don't match." >&2; exit 1; }
read -rp "Your name, as it should appear in the certificate: " NAME

keytool -genkeypair -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASSWORD" -keypass "$PASSWORD" -dname "CN=${NAME:-Claude Auto}, O=Claude Auto"

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set CLAUDE_AUTO_KEYSTORE_BASE64
printf '%s' "$PASSWORD" | gh secret set CLAUDE_AUTO_KEYSTORE_PASSWORD
printf '%s' "$ALIAS" | gh secret set CLAUDE_AUTO_KEY_ALIAS
printf '%s' "$PASSWORD" | gh secret set CLAUDE_AUTO_KEY_PASSWORD

echo
echo "Done. The upload key is in $KEYSTORE and the four signing secrets are set on GitHub."
echo "Back it up now; don't commit it."
