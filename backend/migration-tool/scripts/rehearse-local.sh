#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
backend=$(cd "$script_dir/../.." && pwd -P)

command -v docker >/dev/null
command -v zstd >/dev/null
"$backend/gradlew" -p "$backend" --no-daemon --console=plain :migration-tool:test
