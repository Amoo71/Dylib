#!/usr/bin/env bash
# Baut nxnatives Shader-Übersetzer (dmsh_xlate) als NativeAOT-Bibliothek für iOS (Gerät + Simulator).
# Voraussetzung: scripts/fetch_deps.sh (Vendor/ns2ios-shell) und ein .NET-10-SDK.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=Vendor/ns2ios-shell/dm/gfx/tools/dmsh_xlate/Program.cs
mkdir -p xlate/gen
sed 's/^    static int Main(string\[\] args)/    internal static int Main(string[] args)/' "$SRC" > xlate/gen/Program.cs
grep -q "internal static int Main(string\[\] args)" xlate/gen/Program.cs || { echo "Main() not found in $SRC"; exit 1; }
for rid in ios-arm64 iossimulator-arm64; do
  dotnet publish xlate/NSPiOSXlate.csproj -c Release -r "$rid" -o "Vendor/xlate/$rid" -nologo -v minimal
  ls -la "Vendor/xlate/$rid"
  test -f "Vendor/xlate/$rid/NSPiOSXlate.dylib"
done
