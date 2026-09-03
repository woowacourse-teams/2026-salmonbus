#!/usr/bin/env python3
"""Refresh the 2026-09-02 S3 catch-up closure with route-specific target boundaries."""

from __future__ import annotations

import argparse
import json
import os
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import audit_source
from current_public.data_pipeline import export_current as source
from current_public.evaluation.pipeline import load_protocol
from current_public.evaluation.station_quarantine import exclude_station_mismatch_records


UTC = timezone.utc


def instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(UTC)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--date", required=True)
    parser.add_argument("--snapshot-at", required=True)
    parser.add_argument("--boundary-3330", required=True)
    parser.add_argument("--boundary-1650-lower", required=True)
    parser.add_argument("--boundary-1650-upper", required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output must not exist")
    snapshot_at = instant(args.snapshot_at)
    lower = {"3330": instant(args.boundary_3330), "1650": instant(args.boundary_1650_lower)}
    upper = {"3330": instant(args.boundary_3330), "1650": instant(args.boundary_1650_upper)}
    protocol = load_protocol(args.protocol)
    client = source._s3_client(args.region)
    started = time.monotonic()
    routes: dict[str, object] = {}

    for route in source.ROUTES:
        record_inventory = source.list_frozen_objects(
            client, args.bucket, f"records/route={route}/dt={args.date}/", snapshot_at
        )
        raw_inventory = source.list_frozen_objects(
            client, args.bucket, f"raw/route={route}/dt={args.date}/", snapshot_at
        )
        records = source.read_frozen_objects(client, args.bucket, record_inventory, args.workers)
        raw = source.read_frozen_objects(client, args.bucket, raw_inventory, args.workers)
        raw_info = {item.key: item for item in raw_inventory}
        record_ids: set[str] = set()
        token_to_hmac: dict[str, str] = {}
        hmac_to_token: dict[str, str] = {}
        accepted = {"catchUp": [], "ambiguous": [], "overlap": []}
        accepted_raw = {"catchUp": {}, "ambiguous": {}, "overlap": {}}
        counts = {name: Counter() for name in accepted}
        ranges = {name: [None, None] for name in accepted}
        invalid = Counter()
        for info in sorted(record_inventory, key=lambda item: item.key):
            try:
                item = audit_source.validate_record(records[info.key], raw, snapshot_at)
                record = item.record
                if record.record_id in record_ids:
                    raise source.ValidationError("invalid_or_duplicate_record_identifier")
                if record.received_at < lower[route]:
                    cohort = "catchUp"
                elif record.received_at < upper[route]:
                    cohort = "ambiguous"
                else:
                    cohort = "overlap"
                quarantine = exclude_station_mismatch_records(
                    [record], protocol, timezone_kst=audit_source.KST
                )
                if quarantine.excluded_records:
                    counts[cohort]["quarantinedRecords"] += 1
                    counts[cohort]["stationMismatchRows"] += quarantine.excluded_observations
                    counts[cohort]["quarantinedObservationRows"] += len(record.observations)
                    continue
                pairs = [
                    (observation.private_token, observation.vehicle_hmac)
                    for observation in record.observations
                ]
                if any(
                    hmac_value is None
                    or token_to_hmac.get(token, hmac_value) != hmac_value
                    or hmac_to_token.get(hmac_value, token) != token
                    for token, hmac_value in pairs
                ):
                    raise source.ValidationError("vehicle_hmac_identity_conflict")
                for token, hmac_value in pairs:
                    assert hmac_value is not None
                    token_to_hmac[token] = hmac_value
                    hmac_to_token[hmac_value] = token
                record_ids.add(record.record_id)
                accepted[cohort].append(info)
                if item.response_key is not None:
                    response = raw_info.get(item.response_key)
                    if response is None or item.response_key in accepted_raw[cohort]:
                        raise source.ValidationError("accepted_response_bijection_failure")
                    accepted_raw[cohort][item.response_key] = response
                counts[cohort]["observations"] += len(record.observations)
                counts[cohort]["rawLessRecords"] += int(item.response_key is None)
                bounds = ranges[cohort]
                bounds[0] = record.received_at if bounds[0] is None else min(bounds[0], record.received_at)
                bounds[1] = record.received_at if bounds[1] is None else max(bounds[1], record.received_at)
            except source.ValidationError as error:
                invalid[error.code] += 1
        cohorts = {}
        for name in ("catchUp", "ambiguous", "overlap"):
            inventory = [*accepted[name], *accepted_raw[name].values()]
            cohorts[name] = {
                "acceptedRecordDocuments": len(accepted[name]),
                "acceptedRawDocuments": len(accepted_raw[name]),
                "acceptedRawLessRecords": counts[name]["rawLessRecords"],
                "acceptedObservations": counts[name]["observations"],
                "quarantinedRecords": counts[name]["quarantinedRecords"],
                "stationMismatchRows": counts[name]["stationMismatchRows"],
                "quarantinedObservationRows": counts[name]["quarantinedObservationRows"],
                "acceptedManifestSha256": source.manifest_sha256(inventory),
                "responseRangeUtcInclusive": [
                    None if ranges[name][0] is None else ranges[name][0].isoformat(),
                    None if ranges[name][1] is None else ranges[name][1].isoformat(),
                ],
            }
        routes[route] = {
            "boundaryLowerUtc": lower[route].isoformat().replace("+00:00", "Z"),
            "boundaryUpperUtc": upper[route].isoformat().replace("+00:00", "Z"),
            "membershipInvariantAcrossBoundaryBracket": len(accepted["ambiguous"]) == 0
            and counts["ambiguous"]["quarantinedRecords"] == 0,
            "inventory": {
                "recordDocuments": len(record_inventory),
                "rawDocuments": len(raw_inventory),
                "bytes": sum(item.size for item in (*record_inventory, *raw_inventory)),
                "sha256": source.manifest_sha256((*record_inventory, *raw_inventory)),
            },
            "cohorts": cohorts,
            "invalidRecordsByCode": dict(sorted(invalid.items())),
        }
        token_to_hmac.clear()
        hmac_to_token.clear()
        del records, raw

    receipt = {
        "schemaVersion": "v4-1-route-specific-catch-up-audit-v1",
        "classification": "REFRESHED_ROUTE_SPECIFIC_SOURCE_TARGET_AUTHORITY",
        "partitionDateKst": args.date,
        "snapshotAtUtc": snapshot_at.isoformat().replace("+00:00", "Z"),
        "routes": routes,
        "privacy": {
            "rowLevelMaterialPersisted": False,
            "vehicleIdsEmitted": False,
            "vehicleHmacsEmitted": False,
            "plateValuesEmitted": False,
            "objectKeysEmitted": False,
        },
        "runtime": {"durationSeconds": round(time.monotonic() - started, 3), "workers": args.workers},
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    os.replace(temporary, args.output)
    print(
        json.dumps(
            {
                route: {
                    "catchUpRecords": routes[route]["cohorts"]["catchUp"]["acceptedRecordDocuments"],
                    "catchUpObservations": routes[route]["cohorts"]["catchUp"]["acceptedObservations"],
                    "ambiguousRecords": routes[route]["cohorts"]["ambiguous"]["acceptedRecordDocuments"],
                }
                for route in source.ROUTES
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
