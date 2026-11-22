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
# ns2ios-shell (nxnative ohne Mac): Packer-Quellen; die Shell-App lädt NSPiOS zur Laufzeit aus demselben Release
NS2IOS_TAG=v0.0.0047

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

clone ns2ios-shell "$NS2IOS_TAG" https://github.com/xinerqu/ns2ios-shell

# Nur die Teile des Packers, die beim Packen gebraucht werden (das ganze Repo hat ~800 MB Spielprofile)
rm -rf py/packer
mkdir -p py/packer/dm/games py/packer/dm/gfx/nvn
cp -R Vendor/ns2ios-shell/scripts py/packer/
cp -R Vendor/ns2ios-shell/dm/tools Vendor/ns2ios-shell/dm/host Vendor/ns2ios-shell/dm/mac py/packer/dm/
mkdir -p py/packer/dm/gfx py/packer/dm/ios
cp -R Vendor/ns2ios-shell/dm/gfx/tools py/packer/dm/gfx/
cp -R Vendor/ns2ios-shell/dm/gfx/nvn/. py/packer/dm/gfx/nvn/
cp -R Vendor/ns2ios-shell/dm/ios/tools py/packer/dm/ios/
find Vendor/ns2ios-shell/dm/games -maxdepth 1 -type f -exec cp {} py/packer/dm/games/ \;
for tid in 0100FD8022DAA000 010099C022B96000; do   # Super Mario Galaxy 2, Super Mario Galaxy
  cp -R "Vendor/ns2ios-shell/dm/games/$tid" py/packer/dm/games/
done
# Spieleliste aller Profile (Name, Grafikweg, Status); die Profile selbst lädt NSPiOS bei Bedarf
python3 -I scripts/make_profile_index.py Vendor/ns2ios-shell/dm/games py/packer/profiles_index.tsv

# Version + Prüfsumme der Dateien: NSPiOS erneuert seine Packer-Kopie, sobald sich der Inhalt ändert
SUM=$(cd py/packer && find . -type f -exec shasum {} + | LC_ALL=C sort | shasum | cut -c1-12)
echo "$NS2IOS_TAG-$SUM" > py/packer/NSPIOS_PACKER_TAG
cp Vendor/ns2ios-shell/LICENSE py/packer/LICENSE
find py/packer -name '__pycache__' -type d -prune -exec rm -rf {} +
find py/packer -name '*.exe' -delete
echo "Packer: $(du -sh py/packer | cut -f1)"

rm -rf py/capstone
cp -R Vendor/capstone/bindings/python/capstone py/capstone
python3 scripts/patch_vendor.py

echo "Python.xcframework:"; ls Vendor/Python.xcframework
