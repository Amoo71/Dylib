#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST_DIR="$ROOT_DIR/ios/App/Resources/Web"

mkdir -p "$DEST_DIR"
cp "$ROOT_DIR/index.html" "$DEST_DIR/index.html"
cp "$ROOT_DIR/styles.css" "$DEST_DIR/styles.css"
cp "$ROOT_DIR/script.js" "$DEST_DIR/script.js"
