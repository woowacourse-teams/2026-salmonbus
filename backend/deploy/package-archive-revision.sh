#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
commit="${1:?커밋 SHA가 필요하다}"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || exit 1
source deploy/runtime-source-inputs.sh
jar="$(ls archive-app/build/libs/archive-app-*.jar | grep -v -- '-plain.jar$')"
out=build/revision/archive
mkdir -p "$out/jars" "$out/systemd"
cp "$jar" "$out/jars/archive-app.jar"
cp deploy/systemd/salmonbus-archive.service "$out/systemd/"
printf 'component=archive\ncommit=%s\nsourceDigest=%s\nartifactSha256=%s\n' \
 "$commit" "$(runtime_source_digest archive)" "$(sha256sum "$jar" | cut -d' ' -f1)" > "$out/release-manifest.txt"
