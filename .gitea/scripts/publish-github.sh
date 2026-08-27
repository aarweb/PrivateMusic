#!/usr/bin/env bash
set -euo pipefail

: "${RELEASE_TAG:=${GITHUB_REF_NAME:-}}"
: "${RELEASE_TAG:?Falta RELEASE_TAG}"
: "${GH_RELEASE_TOKEN:?Falta GH_RELEASE_TOKEN}"
: "${GITHUB_RELEASE_REPOSITORY:?Falta GITHUB_RELEASE_REPOSITORY}"

api="https://api.github.com/repos/$GITHUB_RELEASE_REPOSITORY"
headers=(
  -H "Authorization: Bearer $GH_RELEASE_TOKEN"
  -H "Accept: application/vnd.github+json"
  -H "X-GitHub-Api-Version: 2022-11-28"
)

release_json="$(curl --silent --show-error --fail "${headers[@]}" \
  "$api/releases/tags/$RELEASE_TAG" 2>/dev/null || true)"
if [[ -z "$release_json" ]]; then
  notes="$(git log -1 --format='%s')"
  payload="$(jq -n --arg tag "$RELEASE_TAG" --arg name "$RELEASE_TAG" \
    --arg body "$notes" '{tag_name:$tag,name:$name,body:$body,draft:false,prerelease:false}')"
  release_json="$(curl --silent --show-error --fail "${headers[@]}" \
    -X POST "$api/releases" -d "$payload")"
fi

release_id="$(jq -r .id <<<"$release_json")"
upload_url="$(jq -r '.upload_url | sub("\\{.*"; "")' <<<"$release_json")"
asset="$(find dist -maxdepth 1 -name 'privatemusic-*.apk' -print -quit)"
[[ -n "$asset" ]] || { echo "No se encontró el APK" >&2; exit 1; }
name="$(basename "$asset")"

old_id="$(curl --silent --show-error --fail "${headers[@]}" \
  "$api/releases/$release_id/assets" | jq -r --arg name "$name" \
  '.[] | select(.name == $name) | .id' | head -1)"
if [[ -n "$old_id" ]]; then
  curl --silent --show-error --fail "${headers[@]}" \
    -X DELETE "$api/releases/assets/$old_id" >/dev/null
fi

encoded_name="$(printf '%s' "$name" | jq -sRr @uri)"
curl --silent --show-error --fail "${headers[@]}" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$asset" "$upload_url?name=$encoded_name" >/dev/null
echo "Publicado $name en GitHub Releases"
