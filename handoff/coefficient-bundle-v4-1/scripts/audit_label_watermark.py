#!/usr/bin/env python3
"""Audit the final prediction date's label-only S3 watermark slice."""

from __future__ import annotations

import argparse
import json
import os
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

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
    parser.add_argument("--watermark", required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output must not exist")
    watermark = datetime.fromisoformat(args.watermark.replace("Z", "+00:00")).astimezone(UTC)
    protocol = load_protocol(args.protocol)
    client = source._s3_client(args.region)
    started = time.monotonic()
    record_inventory: list[source.ObjectInfo] = []
    raw_inventory: list[source.ObjectInfo] = []
    for route in source.ROUTES:
        record_inventory.extend(
            source.list_frozen_objects(
                client, args.bucket, f"records/route={route}/dt={args.date}/", watermark
            )
        )
        raw_inventory.extend(
            source.list_frozen_objects(
                client, args.bucket, f"raw/route={route}/dt={args.date}/", watermark
            )
        )
    record_inventory.sort(key=lambda item: item.key)
    raw_inventory.sort(key=lambda item: item.key)
    records = source.read_frozen_objects(client, args.bucket, record_inventory, args.workers)
    raw = source.read_frozen_objects(client, args.bucket, raw_inventory, args.workers)
    raw_info = {item.key: item for item in raw_inventory}
    accepted_records: list[source.ObjectInfo] = []
    accepted_raw: dict[str, source.ObjectInfo] = {}
    invalid = Counter()
    outcomes = Counter()
    strategies = Counter()
    counts = Counter()
    record_ids: set[str] = set()
    token_to_hmac: dict[str, str] = {}
    hmac_to_token: dict[str, str] = {}
    observed_from = None
    observed_through = None
    for info in record_inventory:
        try:
            item = audit_source.validate_record(records[info.key], raw, watermark)
            record = item.record
            if record.record_id in record_ids:
                raise source.ValidationError("invalid_or_duplicate_record_identifier")
            quarantine = exclude_station_mismatch_records(
                [record], protocol, timezone_kst=audit_source.KST
            )
            if quarantine.excluded_records:
                counts["station_mismatch_records"] += quarantine.excluded_records
                counts["station_mismatch_observations"] += quarantine.excluded_observations
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
            accepted_records.append(info)
            if item.response_key is not None:
                response = raw_info.get(item.response_key)
                if response is None or item.response_key in accepted_raw:
                    raise source.ValidationError("accepted_response_bijection_failure")
                accepted_raw[item.response_key] = response
            outcomes[record.semantic_outcome] += 1
            strategies[record.strategy_version] += 1
            counts["observations"] += len(record.observations)
            counts["seat_missing_rows"] += record.seat_missing_rows
            counts["repaired_partition_crossings"] += int(item.repaired_partition_crossing)
            observed_from = (
                record.received_at
                if observed_from is None or record.received_at < observed_from
                else observed_from
            )
            observed_through = (
                record.received_at
                if observed_through is None or record.received_at > observed_through
                else observed_through
            )
        except source.ValidationError as error:
            invalid[error.code] += 1
    accepted_inventory = [*accepted_records, *accepted_raw.values()]
    receipt = {
        "schemaVersion": "v4-1-label-only-watermark-audit-v1",
        "classification": "LABEL_ONLY_NO_PREDICTION_DATE",
        "dateKst": args.date,
        "watermarkAtUtc": watermark.isoformat().replace("+00:00", "Z"),
        "requiredBy": "last prediction date end + 2h + max(G=600s) + max(S=300s)",
        "inventory": {
            "recordDocuments": len(record_inventory),
            "rawDocuments": len(raw_inventory),
            "bytes": sum(item.size for item in (*record_inventory, *raw_inventory)),
            "sha256": source.manifest_sha256((*record_inventory, *raw_inventory)),
        },
        "accepted": {
            "recordDocuments": len(accepted_records),
            "rawDocuments": len(accepted_raw),
            "observations": counts["observations"],
            "seatMissingRows": counts["seat_missing_rows"],
            "sha256": source.manifest_sha256(accepted_inventory),
            "observedFromUtc": None if observed_from is None else observed_from.isoformat(),
            "observedThroughUtc": None if observed_through is None else observed_through.isoformat(),
        },
        "invalidRecordsByCode": dict(sorted(invalid.items())),
        "repairedPartitionCrossings": counts["repaired_partition_crossings"],
        "stationMismatchRecords": counts["station_mismatch_records"],
        "stationMismatchObservations": counts["station_mismatch_observations"],
        "semanticOutcomes": dict(sorted(outcomes.items())),
        "strategies": dict(sorted(strategies.items())),
        "predictionRowsOpened": 0,
        "privacy": {
            "rowLevelMaterialPersisted": False,
            "objectKeysEmitted": False,
            "vehicleHmacsEmitted": False,
            "originalVehicleIdsEmitted": False,
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
                "acceptedManifestSha256": receipt["accepted"]["sha256"],
                "acceptedObservations": receipt["accepted"]["observations"],
                "status": "succeeded",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
