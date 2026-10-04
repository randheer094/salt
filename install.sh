#!/usr/bin/env bash
# install.sh        build, install to ~/.local (command: salt) and print how to start it
# install.sh --dev  build and run from this checkout, with data in ~/.salt_dev
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "--dev" ]; then
  export SALT_HOME="${SALT_HOME:-$HOME/.salt_dev}"
  echo "Dev mode: data in $SALT_HOME"
  exec ./gradlew :server:run
fi
[ $# -eq 0 ] || { echo "usage: install.sh [--dev]" >&2; exit 1; }

./gradlew :server:installDist
share="$HOME/.local/share/salt"
mkdir -p "$share" "$HOME/.local/bin"
rm -rf "$share"
cp -R server/build/install/server "$share"
ln -sf "$share/bin/server" "$HOME/.local/bin/salt"

echo
echo "Installed. Start Salt with:"
echo "  salt"
echo "then open http://127.0.0.1:8080 (data in ~/.salt)."
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) echo "Add ~/.local/bin to your PATH first: export PATH=\"\$HOME/.local/bin:\$PATH\"" ;; esac
