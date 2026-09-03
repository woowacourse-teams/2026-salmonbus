#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  printf 'usage: %s SALMONBUS_ANALYSIS_REPOSITORY BUNDLE_DIRECTORY\n' "$0" >&2
  exit 2
fi

readonly ANALYSIS_REPOSITORY=$1
readonly BUNDLE_DIRECTORY=$2
readonly PRODUCER_COMMIT="c5ff99da81ed0af9916bd5aa5115d89f49258e2c"

git -C "$ANALYSIS_REPOSITORY" cat-file -e "$PRODUCER_COMMIT^{commit}"
task_tmp=$(mktemp -d)
cleanup() {
  find "$task_tmp" -type f -delete 2>/dev/null || true
  find "$task_tmp" -depth -type d -empty -delete 2>/dev/null || true
}
trap cleanup EXIT

git -C "$ANALYSIS_REPOSITORY" archive "$PRODUCER_COMMIT" server/model | tar -x -C "$task_tmp"
PYTHONPATH="$task_tmp/server" python3 - "$BUNDLE_DIRECTORY" <<'PY'
from __future__ import annotations

import json
import math
import pathlib
import sys
from datetime import datetime, timezone

from model.bundle import load_bundle


bundle = pathlib.Path(sys.argv[1])
predictor = load_bundle(bundle / "manifest.json", bundle / "weights.safetensors")
reference = predictor.bundle.route_reference
checks = []

for route in ("1650", "3330"):
    maximum = reference.routes[route].maximum_station
    for horizon in (1, 4, 8, 12):
        target = next(
            station
            for station in range(horizon + 1, maximum + 1)
            if reference.is_boarding(route, station)
        )
        result = predictor.predict_distribution(
            {
                "route": route,
                "target_sequence": target,
                "horizon": horizon,
                "predicted_at": datetime(2026, 8, 23, 3, 0, tzinfo=timezone.utc),
                "current_seats": 20,
                "capacity": 44,
                "crowded": 0,
                "slope": 0.0,
                "full_streak": 0,
                "previous_full": 0,
                "previous_seats": 20,
                "cell_occupancy": 0.0,
                "segment_boarding": 0.0,
                "cell_missing": False,
                "state_cd": 2,
            }
        )
        probability_sum = sum(result.pmf)
        checks.append(
            {
                "route": route,
                "horizon": horizon,
                "pmf_cells": len(result.pmf),
                "pmf_sum": probability_sum,
                "pmf_sum_within_1e_12": abs(probability_sum - 1.0) <= 1e-12,
                "p0_equals_p_full_within_1e_12": abs(result.pmf[0] - result.p_full) <= 1e-12,
                "all_probabilities_finite": all(math.isfinite(value) for value in result.pmf),
                "all_probabilities_in_range": all(0.0 <= value <= 1.0 for value in result.pmf),
                "expected_seats_in_range": 0.0 <= result.expected_seats <= 70.0,
            }
        )

passed = all(
    item["pmf_sum_within_1e_12"]
    and item["p0_equals_p_full_within_1e_12"]
    and item["all_probabilities_finite"]
    and item["all_probabilities_in_range"]
    and item["expected_seats_in_range"]
    for item in checks
)
print(
    json.dumps(
        {
            "status": "PASS" if passed else "FAIL",
            "release_id": predictor.release_id,
            "bundle_digest": predictor.bundle_digest,
            "contexts": checks,
        },
        indent=2,
        sort_keys=True,
    )
)
raise SystemExit(0 if passed else 1)
PY
