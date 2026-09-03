#!/usr/bin/env python3
"""Record an aggregate-only S3 object-inventory observation for freeze stability."""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime, timezone
from pathlib import Path

from current_public.data_pipeline import export_current as source


UTC = timezone.utc


def instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--date", required=True)
    parser.add_argument("--observed-at", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output must not exist")
    observed_at = instant(args.observed_at)
    client = source._s3_client(args.region)
    combined = []
    routes: dict[str, object] = {}
    for route in source.ROUTES:
        kinds: dict[str, object] = {}
        route_inventory = []
        for kind in ("records", "raw"):
            inventory = source.list_frozen_objects(
                client,
                args.bucket,
                f"{kind}/route={route}/dt={args.date}/",
                observed_at,
            )
            route_inventory.extend(inventory)
            combined.extend(inventory)
            kinds[kind] = {
                "documents": len(inventory),
                "bytes": sum(item.size for item in inventory),
                "manifestSha256": source.manifest_sha256(inventory),
                "lastModifiedMaxUtc": max(
                    (item.last_modified for item in inventory), default=None
                ),
            }
        for value in kinds.values():
            latest = value["lastModifiedMaxUtc"]
            value["lastModifiedMaxUtc"] = (
                None if latest is None else latest.isoformat().replace("+00:00", "Z")
            )
        routes[route] = {
            "kinds": kinds,
            "documents": len(route_inventory),
            "bytes": sum(item.size for item in route_inventory),
            "manifestSha256": source.manifest_sha256(route_inventory),
        }
    receipt = {
        "schemaVersion": "s3-freeze-inventory-observation-v1",
        "classification": "READ_ONLY_STABILITY_OBSERVATION",
        "partitionDateKst": args.date,
        "observedAtUtc": observed_at.isoformat().replace("+00:00", "Z"),
        "routes": routes,
        "combined": {
            "documents": len(combined),
            "bytes": sum(item.size for item in combined),
            "manifestSha256": source.manifest_sha256(combined),
        },
        "privacy": {
            "objectKeysEmitted": False,
            "rowLevelMaterialRead": False,
            "rowLevelMaterialPersisted": False,
            "vehicleIdsEmitted": False,
            "vehicleHmacsEmitted": False,
            "plateValuesEmitted": False,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    os.replace(temporary, args.output)
    print(
        json.dumps(
            {
                "observedAtUtc": receipt["observedAtUtc"],
                "documents": receipt["combined"]["documents"],
                "bytes": receipt["combined"]["bytes"],
                "manifestSha256": receipt["combined"]["manifestSha256"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
