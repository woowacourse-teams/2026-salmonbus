#!/usr/bin/env python3
"""Read-only dry-run validation for the aggregate cell-history seed."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import re
from collections import Counter
from datetime import datetime
from pathlib import Path


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seed", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    compressed = args.seed.read_bytes()
    payload = gzip.decompress(compressed)
    document = json.loads(payload)
    declared = json.loads(args.receipt.read_text(encoding="utf-8"))
    checks = {
        "schema": document["schemaVersion"] == "stop-demand-hourly-aggregate-seed-v1",
        "dryRunOnly": document["classification"]
        == "PRIVACY_SAFE_AGGREGATE_BACKFILL_SEED_DRY_RUN_ONLY",
        "compressedDigest": hashlib.sha256(compressed).hexdigest()
        == declared["compressedSha256"],
        "canonicalDigest": hashlib.sha256(payload).hexdigest()
        == declared["canonicalJsonSha256"],
        "rowCount": len(document["rows"]) == declared["rowCount"],
        "privacyFlags": all(
            value is False for value in document["privacy"].values()
        ),
    }
    seen = set()
    route_samples = Counter()
    minimum_fill = math.inf
    maximum_fill = -math.inf
    minimum_net = math.inf
    maximum_net = -math.inf
    for row in document["rows"]:
        hour = datetime.fromisoformat(row["arrivalHourStartUtc"].replace("Z", "+00:00"))
        key = (row["modelRoute"], row["stopOrder"], row["arrivalHourStartUtc"])
        if key in seen:
            raise ValueError("duplicate_seed_key")
        seen.add(key)
        values = (
            float(row["fillRateTotal"]),
            float(row["netBoardingTotal"]),
            float(row["capacityTotal"]),
        )
        if not all(math.isfinite(value) for value in values):
            raise ValueError("non_finite_seed_value")
        count = int(row["sampleCount"])
        if (
            row["modelRoute"] not in ("1650", "3330")
            or not 1 <= int(row["stopOrder"]) <= 89
            or hour.minute != 0
            or hour.second != 0
            or count <= 0
            or values[2] <= 0.0
            or values[0] < -1e-12
            or values[0] > count + 1e-12
            or abs(values[1]) > values[2] + 1e-12
        ):
            raise ValueError("seed_row_range_failure")
        route_samples[row["modelRoute"]] += count
        minimum_fill = min(minimum_fill, values[0] / count)
        maximum_fill = max(maximum_fill, values[0] / count)
        minimum_net = min(minimum_net, values[1] / values[2])
        maximum_net = max(maximum_net, values[1] / values[2])
    forbidden_patterns = {
        "hmacValue": rb"hmac-sha256:[0-9a-f]{64}",
        "awsAccessKey": rb"AKIA[0-9A-Z]{16}",
        "rawVehicleField": rb'\"(?:vehId|plateNo)\"\s*:',
        "collectorObjectKey": rb"(?:records|raw)/route=[0-9]+/dt=",
    }
    privacy_scan = {
        name: re.search(pattern, payload, flags=re.IGNORECASE) is None
        for name, pattern in forbidden_patterns.items()
    }
    checks["contentPrivacyScan"] = all(privacy_scan.values())
    if not all(checks.values()):
        raise ValueError("seed_validation_failed")
    result = {
        "schemaVersion": "stop-demand-hourly-aggregate-seed-validation-v1",
        "status": "PASS",
        "databaseWritePerformed": False,
        "checks": checks,
        "privacyScan": privacy_scan,
        "rowCount": len(document["rows"]),
        "routeSamples": dict(sorted(route_samples.items())),
        "rates": {
            "minimumHourlyAverageFill": minimum_fill,
            "maximumHourlyAverageFill": maximum_fill,
            "minimumHourlyNetBoardingRate": minimum_net,
            "maximumHourlyNetBoardingRate": maximum_net,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(
        json.dumps(result, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, args.output)
    print(json.dumps({"status": "PASS", "rowCount": len(document["rows"])}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
