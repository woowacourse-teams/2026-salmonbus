#!/usr/bin/env python3
"""Audit the S3 catch-up authority before the first academy RDS response."""

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


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--date", required=True)
    parser.add_argument("--snapshot-at", required=True)
    parser.add_argument("--target-authority-from", required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output must not exist")
    snapshot_at = datetime.fromisoformat(args.snapshot_at.replace("Z", "+00:00")).astimezone(UTC)
    target_from = datetime.fromisoformat(
        args.target_authority_from.replace("Z", "+00:00")
    ).astimezone(UTC)
    protocol = load_protocol(args.protocol)
    client = source._s3_client(args.region)
    started = time.monotonic()
    record_inventory: list[source.ObjectInfo] = []
    raw_inventory: list[source.ObjectInfo] = []
    for route in source.ROUTES:
        record_inventory.extend(
            source.list_frozen_objects(
                client, args.bucket, f"records/route={route}/dt={args.date}/", snapshot_at
            )
        )
        raw_inventory.extend(
            source.list_frozen_objects(
                client, args.bucket, f"raw/route={route}/dt={args.date}/", snapshot_at
            )
        )
    record_inventory.sort(key=lambda item: item.key)
    raw_inventory.sort(key=lambda item: item.key)
    records = source.read_frozen_objects(client, args.bucket, record_inventory, args.workers)
    raw = source.read_frozen_objects(client, args.bucket, raw_inventory, args.workers)
    raw_info = {item.key: item for item in raw_inventory}
    record_ids: set[str] = set()
    token_to_hmac: dict[str, str] = {}
    hmac_to_token: dict[str, str] = {}
    accepted = {"catchUp": [], "overlap": []}
    accepted_raw = {"catchUp": {}, "overlap": {}}
    counts = {"catchUp": Counter(), "overlap": Counter()}
    invalid = Counter()
    response_ranges: dict[str, list[datetime | None]] = {
        "catchUp": [None, None],
        "overlap": [None, None],
    }
    for info in record_inventory:
        try:
            item = audit_source.validate_record(records[info.key], raw, snapshot_at)
            record = item.record
            if record.record_id in record_ids:
                raise source.ValidationError("invalid_or_duplicate_record_identifier")
            cohort = "catchUp" if record.received_at < target_from else "overlap"
            quarantine = exclude_station_mismatch_records(
                [record], protocol, timezone_kst=audit_source.KST
            )
            if quarantine.excluded_records:
                counts[cohort]["quarantinedRecords"] += quarantine.excluded_records
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
            counts[cohort]["failedRecords"] += int(record.semantic_outcome == "failed")
            bounds = response_ranges[cohort]
            bounds[0] = record.received_at if bounds[0] is None else min(bounds[0], record.received_at)
            bounds[1] = record.received_at if bounds[1] is None else max(bounds[1], record.received_at)
        except source.ValidationError as error:
            invalid[error.code] += 1

    cohorts = {}
    for cohort in ("catchUp", "overlap"):
        inventory = [*accepted[cohort], *accepted_raw[cohort].values()]
        bounds = response_ranges[cohort]
        cohorts[cohort] = {
            "acceptedRecordDocuments": len(accepted[cohort]),
            "acceptedRawDocuments": len(accepted_raw[cohort]),
            "acceptedRawLessRecords": counts[cohort]["rawLessRecords"],
            "acceptedObservations": counts[cohort]["observations"],
            "failedRecords": counts[cohort]["failedRecords"],
            "quarantinedRecords": counts[cohort]["quarantinedRecords"],
            "stationMismatchRows": counts[cohort]["stationMismatchRows"],
            "quarantinedObservationRows": counts[cohort]["quarantinedObservationRows"],
            "acceptedManifestSha256": source.manifest_sha256(inventory),
            "responseReceivedAtRangeUtcInclusive": [
                None if bounds[0] is None else bounds[0].isoformat(),
                None if bounds[1] is None else bounds[1].isoformat(),
            ],
        }
    receipt = {
        "schemaVersion": "v4-1-s3-catch-up-authority-audit-v1",
        "classification": "FULL_HISTORY_CATCH_UP_AND_RECONCILIATION_SPLIT",
        "partitionDateKst": args.date,
        "snapshotObservedAtUtc": snapshot_at.isoformat().replace("+00:00", "Z"),
        "targetAuthorityFromUtcInclusive": target_from.isoformat().replace("+00:00", "Z"),
        "inventory": {
            "recordDocuments": len(record_inventory),
            "rawDocuments": len(raw_inventory),
            "bytes": sum(item.size for item in (*record_inventory, *raw_inventory)),
            "sha256": source.manifest_sha256((*record_inventory, *raw_inventory)),
        },
        "cohorts": cohorts,
        "invalidRecordsByCode": dict(sorted(invalid.items())),
        "automaticModelInput": "catchUp only; overlap is evidence only",
        "privacy": {
            "rowLevelMaterialPersisted": False,
            "objectKeysEmitted": False,
            "vehicleIdsEmitted": False,
            "vehicleHmacsEmitted": False,
            "plateValuesEmitted": False,
            "secretsEmitted": False,
        },
        "runtime": {
            "durationSeconds": round(time.monotonic() - started, 3),
            "workers": args.workers,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, args.output)
    token_to_hmac.clear()
    hmac_to_token.clear()
    print(
        json.dumps(
            {
                "catchUpRecords": cohorts["catchUp"]["acceptedRecordDocuments"],
                "catchUpObservations": cohorts["catchUp"]["acceptedObservations"],
                "status": "succeeded",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
