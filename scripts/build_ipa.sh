#!/usr/bin/env bash
set -euo pipefail

SCHEME="VisionInspect"
PROJECT="VisionInspect.xcodeproj"
DERIVED_DATA_PATH="build"
ARCHIVE_DIR="$DERIVED_DATA_PATH/archive"
APP_PATH="$DERIVED_DATA_PATH/Build/Products/Release-iphoneos/${SCHEME}.app"
PAYLOAD_DIR="$DERIVED_DATA_PATH/Payload"
IPA_PATH="$DERIVED_DATA_PATH/${SCHEME}.ipa"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "xcodegen is required but not installed" >&2
  exit 1
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "xcodebuild is required but not installed" >&2
  exit 1
fi

rm -rf "$DERIVED_DATA_PATH" "$PROJECT"

xcodegen generate

xcodebuild \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -configuration Release \
  -sdk iphoneos \
  -derivedDataPath "$DERIVED_DATA_PATH" \
  CODE_SIGNING_ALLOWED=NO \
  build

if [ ! -d "$APP_PATH" ]; then
  echo "Built app not found at $APP_PATH" >&2
  exit 1
fi

rm -rf "$PAYLOAD_DIR" "$IPA_PATH"
mkdir -p "$PAYLOAD_DIR"
cp -R "$APP_PATH" "$PAYLOAD_DIR/"

(
  cd "$DERIVED_DATA_PATH"
  zip -qry "${SCHEME}.ipa" Payload
)

echo "IPA created at $IPA_PATH"
