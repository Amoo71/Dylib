#!/usr/bin/env bash
# Lädt die Abhängigkeiten der NSPiOS-Engine (nicht im Repo):
#   Python für iOS (BeeWare), capstone (Disassembler), SPIRV-Cross (Shader), lz4.
set -euo pipefail
cd "$(dirname "$0")/.."

PY_TAG=3.13-b18
PY_ASSET=Python-3.13-iOS-support.b18.tar.gz
CAPSTONE_TAG=5.0.6
SPIRV_TAG=vulkan-sdk-1.4.357.0
LZ4_TAG=v1.10.0

mkdir -p Vendor
if [ ! -d Vendor/Python.xcframework ]; then
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/py.tgz" "https://github.com/beeware/Python-Apple-support/releases/download/$PY_TAG/$PY_ASSET"
  tar -xzf "$tmp/py.tgz" -C "$tmp"
  mv "$tmp/Python.xcframework" Vendor/
  rm -rf "$tmp"
fi

clone() { [ -d "Vendor/$1" ] || git clone -q --depth 1 --branch "$2" "$3" "Vendor/$1"; }
clone capstone "$CAPSTONE_TAG" https://github.com/capstone-engine/capstone
clone SPIRV-Cross "$SPIRV_TAG" https://github.com/KhronosGroup/SPIRV-Cross
clone lz4 "$LZ4_TAG" https://github.com/lz4/lz4

rm -rf py/capstone
cp -R Vendor/capstone/bindings/python/capstone py/capstone
python3 scripts/patch_vendor.py

echo "Python.xcframework:"; ls Vendor/Python.xcframework
