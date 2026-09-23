#!/usr/bin/env bash
set -euo pipefail

migration_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -n "${JAVA_HOME:-}" ]]; then
    migration_java="$JAVA_HOME/bin/java"
else
    migration_java="java"
fi

exec "$migration_java" -cp "$migration_dir/lib/*" com.gustler.backend.migration.Sal134Migration "$@"
