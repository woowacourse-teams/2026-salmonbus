#!/usr/bin/env bash
set -euo pipefail

readonly TARGET_COMMIT="d856d10819bf1d018ad43fa63714cc348f1fc643"
readonly TEST_CLASS="com.gustler.backend.processor.seatdistribution.ExternalV41BundleContractTest"

usage() {
  printf 'usage: %s verify|probe|loader-only BUNDLE_DIRECTORY\n' "$0" >&2
  printf '       %s self-test|core-test\n' "$0" >&2
}

if [[ $# -lt 1 ]]; then
  usage
  exit 2
fi
readonly MODE=$1
if [[ "$MODE" != "verify" && "$MODE" != "probe" && "$MODE" != "loader-only" && "$MODE" != "self-test" && "$MODE" != "core-test" ]]; then
  usage
  exit 2
fi
if [[ ("$MODE" == "self-test" || "$MODE" == "core-test") && $# -ne 1 ]]; then
  usage
  exit 2
fi
if [[ "$MODE" != "self-test" && "$MODE" != "core-test" && $# -ne 2 ]]; then
  usage
  exit 2
fi

script_directory=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
repository=$(git -C "$script_directory" rev-parse --show-toplevel)
git -C "$repository" cat-file -e "$TARGET_COMMIT^{commit}"
bundle_directory=""
if [[ "$MODE" != "self-test" && "$MODE" != "core-test" ]]; then
  given_bundle=$2
  bundle_parent=$(CDPATH= cd -- "$(dirname -- "$given_bundle")" && pwd -P)
  bundle_directory="$bundle_parent/$(basename -- "$given_bundle")"
fi

task_directory=$(mktemp -d)
cleanup() {
  find "$task_directory" -depth -delete 2>/dev/null || true
}
trap cleanup EXIT

git -C "$repository" archive "$TARGET_COMMIT" backend | tar -x -C "$task_directory"
test_package="$task_directory/backend/worker-app/src/test/java/com/gustler/backend/processor/seatdistribution"
mkdir -p "$test_package"
cp "$script_directory/ExternalV41BundleContractTest.java" "$test_package/"
cp "$script_directory/ExternalV41FeatureParityTest.java" "$test_package/"

export GRADLE_USER_HOME="${V41_GRADLE_USER_HOME:-$task_directory/gradle-home}"
export V4_1_BUNDLE_DIRECTORY="$bundle_directory"
export V4_1_PROBE_ONLY=false
export V4_1_LOADER_ONLY=false
export V4_1_SELF_TEST=false
if [[ "$MODE" == "probe" ]]; then
  export V4_1_PROBE_ONLY=true
elif [[ "$MODE" == "loader-only" ]]; then
  export V4_1_LOADER_ONLY=true
elif [[ "$MODE" == "self-test" ]]; then
  export V4_1_SELF_TEST=true
fi

gradle_arguments=(
  -p "$task_directory/backend"
  -I "$script_directory/show-test-output.gradle"
  --no-daemon
  --console=plain
  :worker-app:test
)
if [[ "$MODE" == "core-test" ]]; then
  gradle_arguments+=(
    --tests com.gustler.backend.processor.SeatForecastDesignMatrixTest
    --tests com.gustler.backend.processor.seatdistribution.BundleLoaderTest
    --tests com.gustler.backend.processor.seatdistribution.ResidualBinRangeTest
    --tests com.gustler.backend.processor.seatdistribution.ResidualDistributionTest
    --tests com.gustler.backend.processor.seatdistribution.SeatDistributionParityTest
  )
else
  gradle_arguments+=(
    --tests "$TEST_CLASS"
    --tests com.gustler.backend.processor.seatdistribution.ExternalV41FeatureParityTest
  )
fi
"$task_directory/backend/gradlew" "${gradle_arguments[@]}"
