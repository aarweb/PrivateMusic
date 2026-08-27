#!/usr/bin/env bash
set -euo pipefail

apk=app/build/outputs/apk/release/app-release.apk
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
unzip -qo "$apk" 'lib/arm64-v8a/*' -d "$tmp_dir"

failed=0
for so in "$tmp_dir"/lib/arm64-v8a/*.so; do
  name="$(basename "$so")"
  head -c4 "$so" | grep -q ELF || continue
  minimum="$(readelf -lW "$so" | awk '$1=="LOAD"{print $NF}' | sort | head -1)"
  if [[ "$minimum" == "0x1000" && "$name" != libfpcalc.so ]]; then
    echo "::error::$name está alineada a 4 KB"
    failed=1
  elif [[ "$minimum" == "0x1000" ]]; then
    echo "::warning::$name sigue a 4 KB (excepción conocida)"
  fi
done
(( failed == 0 ))

digest="$($ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs "$apk" \
  | sed -n 's/.*SHA-256 digest: \([0-9a-f]*\).*/\1/p' | head -1)"
expected=e1fefae098e1bbab44660dc2527184ea3bbc6dac1d524eaf75b86669dc0b8ed4
[[ "$digest" == "$expected" ]] || { echo "::error::Firma APK incorrecta: $digest"; exit 1; }

version="${GITHUB_REF_NAME#v}"
mkdir -p dist
cp "$apk" "dist/privatemusic-$version.apk"

