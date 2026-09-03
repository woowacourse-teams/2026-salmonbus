#!/usr/bin/env python3
"""Audit the private collector source without persisting row-level material."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import time
from collections import Counter
from dataclasses import dataclass
from datetime import date, datetime, time as clock_time, timedelta, timezone
from pathlib import Path
from typing import Any, Mapping
from zoneinfo import ZoneInfo

from current_public.data_pipeline import export_current as source
from current_public.evaluation.pipeline import load_protocol
from current_public.evaluation.station_quarantine import exclude_station_mismatch_records


UTC = timezone.utc
KST = ZoneInfo("Asia/Seoul")
FREEZE_TIME = clock_time(0, 15)
COLLECTOR_SCHEMA_VERSION = "1.0.0"


@dataclass(frozen=True)
class ValidatedItem:
    record: source.ValidatedRecord
    response_key: str | None
    repaired_partition_crossing: bool


def parse_arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--from-date", required=True)
    parser.add_argument("--through-date", required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def calendar_days(first: str, last: str) -> list[str]:
    start = date.fromisoformat(first)
    stop = date.fromisoformat(last)
    if stop < start:
        raise ValueError("through date precedes from date")
    return [(start + timedelta(days=offset)).isoformat() for offset in range((stop - start).days + 1)]


def freeze_at(day: str) -> datetime:
    following = date.fromisoformat(day) + timedelta(days=1)
    return datetime.combine(following, FREEZE_TIME, tzinfo=KST).astimezone(UTC)


def response_key_for(record_info: source.ObjectInfo) -> str | None:
    match = source.RECORD_PATH.fullmatch(record_info.key)
    if match is None:
        return None
    return source._expected_response_key(match)


def record_document(loaded: source.LoadedObject) -> Mapping[str, Any]:
    document = source._mapping(
        source._json_document(loaded, "invalid_record_json"), "invalid_record"
    )
    if document.get("schema_version") != COLLECTOR_SCHEMA_VERSION:
        raise source.ValidationError("invalid_collector_schema_version")
    return document


def validate_record(
    loaded: source.LoadedObject,
    response_objects: Mapping[str, source.LoadedObject],
    frozen_at: datetime,
) -> ValidatedItem:
    """Use the approved validator, relaxing only a response crossing its start partition.

    Collector keys are based on request_started_at. A response beginning immediately
    before a KST date boundary may finish in the next date. The upstream validator
    requires both timestamps to remain in the start partition and therefore rejects
    an otherwise byte-valid pair. The repair accepts only this crossing: request start
    must still match the key date/hour, all other source checks remain unchanged.
    """

    record_document(loaded)
    counters = source.VerificationCounters()
    try:
        record = source._validate_record(
            loaded,
            response_objects,
            frozen_at,
            set(),
            set(),
            counters,
        )
        return ValidatedItem(
            record=record,
            response_key=response_key_for(loaded.info) if record.has_response_document else None,
            repaired_partition_crossing=False,
        )
    except source.ValidationError as error:
        if error.code != "date_partition_mismatch":
            raise
    record = validate_partition_crossing(loaded, response_objects, frozen_at)
    return ValidatedItem(
        record=record,
        response_key=response_key_for(loaded.info) if record.has_response_document else None,
        repaired_partition_crossing=True,
    )


def validate_partition_crossing(
    loaded: source.LoadedObject,
    response_objects: Mapping[str, source.LoadedObject],
    frozen_at: datetime,
) -> source.ValidatedRecord:
    path_match = source.RECORD_PATH.fullmatch(loaded.info.key)
    if path_match is None:
        raise source.ValidationError("invalid_record_partition")
    document = record_document(loaded)
    record_id = document.get("record_id")
    if not isinstance(record_id, str) or not record_id:
        raise source.ValidationError("invalid_or_duplicate_record_identifier")

    route_node = source._mapping(document.get("route"), "invalid_record_route")
    route = route_node.get("name")
    if route not in source.ROUTES:
        raise source.ValidationError("unapproved_route")
    if route != path_match.group("route") or route != path_match.group("suffix"):
        raise source.ValidationError("route_partition_mismatch")
    route_id = source._scalar_text(route_node.get("route_id"), "missing_route_reference")
    request = source._mapping(document.get("request"), "invalid_request")
    parameters = source._mapping(request.get("parameters"), "invalid_request_parameters")
    if source._scalar_text(parameters.get("routeId"), "missing_request_route") != route_id:
        raise source.ValidationError("request_route_mismatch")

    timing = source._mapping(document.get("timing"), "invalid_timing")
    started_at = source._parse_time_pair(
        timing.get("request_started_at"), "invalid_request_started_at"
    )
    received_at = source._parse_time_pair(
        timing.get("response_received_at"), "invalid_response_received_at"
    )
    if received_at < started_at or received_at > frozen_at:
        raise source.ValidationError("response_time_outside_freeze")
    started_local = started_at.astimezone(KST)
    received_local = received_at.astimezone(KST)
    if (
        started_local.date().isoformat() != path_match.group("date")
        or f"{started_local.hour:02d}" != path_match.group("hour")
        or received_local.date() != started_local.date() + timedelta(days=1)
        or received_at - started_at > timedelta(seconds=30)
    ):
        raise source.ValidationError("unrepairable_date_partition_mismatch")

    record_rows = source._list(document.get("buses"), "invalid_record_rows")
    if not all(isinstance(row, Mapping) for row in record_rows):
        raise source.ValidationError("invalid_record_row")
    classification = source._mapping(document.get("classification"), "invalid_classification")
    reported_count = classification.get("vehicle_count")
    if isinstance(reported_count, bool) or reported_count != len(record_rows):
        raise source.ValidationError("record_row_count_mismatch")
    http = source._mapping(document.get("http"), "invalid_http")
    raw_reference = source._mapping(document.get("raw_response"), "invalid_response_reference")
    response_key = raw_reference.get("s3_key")
    expected_response_key = source._expected_response_key(path_match)
    response_document: Any = None
    has_response_document = isinstance(response_key, str)
    if has_response_document:
        if response_key != expected_response_key:
            raise source.ValidationError("response_reference_mismatch")
        response_loaded = response_objects.get(response_key)
        if response_loaded is None:
            raise source.ValidationError("missing_response_document")
        response_sha = hashlib.sha256(response_loaded.body).hexdigest()
        declared_hashes = (
            http.get("response_sha256"),
            raw_reference.get("sha256"),
            response_loaded.metadata.get("sha256"),
        )
        if any(value != response_sha for value in declared_hashes):
            raise source.ValidationError("response_hash_mismatch")
        if http.get("response_bytes") != len(response_loaded.body):
            raise source.ValidationError("response_byte_count_mismatch")
        if raw_reference.get("exact_http_body_preserved") is not True:
            raise source.ValidationError("response_preservation_flag_mismatch")
        response_document = source._json_document(response_loaded, "invalid_response_json")
        if document.get("response_envelope") != response_document:
            raise source.ValidationError("response_envelope_mismatch")
    else:
        if response_key is not None:
            raise source.ValidationError("invalid_response_reference")
        if (
            http.get("response_bytes") != 0
            or http.get("response_sha256") != hashlib.sha256(b"").hexdigest()
            or raw_reference.get("exact_http_body_preserved") is not False
            or document.get("response_envelope") is not None
        ):
            raise source.ValidationError("invalid_empty_response_record")

    raw_rows = source._raw_bus_rows(response_document)
    if len(raw_rows) != len(record_rows):
        raise source.ValidationError("response_row_count_mismatch")
    for record_row, raw_row in zip(record_rows, raw_rows):
        if any(record_row.get(field) != raw_row.get(field) for field in source.BUS_FIELDS):
            raise source.ValidationError("normalized_row_mismatch")
        if source._scalar_text(record_row.get("routeId"), "missing_row_route") != route_id:
            raise source.ValidationError("row_route_mismatch")

    collection = source._mapping(document.get("collection"), "invalid_collection")
    strategy = collection.get("strategy_version")
    if not isinstance(strategy, str) or source.SAFE_VERSION.fullmatch(strategy) is None:
        raise source.ValidationError("invalid_collection_version")
    semantic_outcome, response_class = source._response_class(
        http.get("status"), response_document, len(raw_rows)
    )
    period_minutes = source._finite_number(collection.get("period_minutes"))
    rounds = source._finite_number(collection.get("rounds_in_invocation"))
    if period_minutes is None or period_minutes <= 0:
        raise source.ValidationError("invalid_collection_period")
    if rounds is None or rounds <= 0 or not rounds.is_integer():
        raise source.ValidationError("invalid_collection_round_count")

    observations: list[source.BusObservation] = []
    seat_missing_rows = 0
    for row in record_rows:
        pseudonyms = source._mapping(row.get("pseudonyms"), "invalid_row_pseudonyms")
        vehicle_hmac = source._strict_hmac(
            pseudonyms.get("vehId_hmac"), "invalid_vehicle_hmac"
        )
        source._strict_hmac(pseudonyms.get("plateNo_hmac"), "invalid_plate_hmac")
        seats = source._finite_number(row.get("remainSeatCnt"))
        if seats is None or seats < 0:
            seats = None
            seat_missing_rows += 1
        token = source._private_token(row.get("vehId"))
        sequence_value = source._finite_number(row.get("stationSeq"))
        state_value = source._finite_number(row.get("stateCd"))
        crowded_value = source._finite_number(row.get("crowded"))
        if token is None or sequence_value is None or state_value is None:
            continue
        if not sequence_value.is_integer() or not state_value.is_integer():
            continue
        sequence = int(sequence_value)
        state = int(state_value)
        if sequence < 1:
            continue
        station_id = row.get("stationId")
        observations.append(
            source.BusObservation(
                private_token=token,
                sequence=sequence,
                state=state,
                last_passed=sequence - 1 if state == 1 else sequence,
                seats=seats,
                station_id=None if station_id is None else str(station_id),
                vehicle_hmac=vehicle_hmac,
                crowded=(
                    int(crowded_value)
                    if crowded_value is not None and crowded_value.is_integer()
                    else None
                ),
            )
        )
    if len({item.private_token for item in observations}) != len(observations):
        raise source.ValidationError("duplicate_private_join_token_in_response")
    return source.ValidatedRecord(
        source_key=loaded.info.key,
        record_id=record_id,
        route=str(route),
        started_at=started_at,
        received_at=received_at,
        strategy_version=strategy,
        semantic_outcome=semantic_outcome,
        response_class=response_class,
        effective_period_seconds=float(period_minutes) * 60.0 / float(rounds),
        observations=observations,
        vehicle_rows=len(record_rows),
        seat_missing_rows=seat_missing_rows,
        has_response_document=has_response_document,
    )


def validate_day(
    client: source.S3Client,
    bucket: str,
    protocol: Mapping[str, Any],
    day: str,
    workers: int,
    global_record_ids: set[str],
    token_to_hmac: dict[str, str],
    hmac_to_token: dict[str, str],
) -> tuple[dict[str, Any], tuple[source.ObjectInfo, ...]]:
    frozen_at = freeze_at(day)
    record_inventory: list[source.ObjectInfo] = []
    raw_inventory: list[source.ObjectInfo] = []
    for route in source.ROUTES:
        record_inventory.extend(
            source.list_frozen_objects(
                client, bucket, f"records/route={route}/dt={day}/", frozen_at
            )
        )
        raw_inventory.extend(
            source.list_frozen_objects(
                client, bucket, f"raw/route={route}/dt={day}/", frozen_at
            )
        )
    record_inventory.sort(key=lambda item: item.key)
    raw_inventory.sort(key=lambda item: item.key)
    record_objects = source.read_frozen_objects(client, bucket, record_inventory, workers)
    raw_objects = source.read_frozen_objects(client, bucket, raw_inventory, workers)

    accepted_records: list[source.ObjectInfo] = []
    accepted_raw: dict[str, source.ObjectInfo] = {}
    invalid = Counter()
    strategies = Counter()
    outcomes = Counter()
    observations = 0
    seat_missing = 0
    repaired_crossings = 0
    station_mismatch_records = 0
    station_mismatch_observations = 0
    quarantined_record_observations = 0

    raw_info_by_key = {item.key: item for item in raw_inventory}
    for info in record_inventory:
        try:
            item = validate_record(record_objects[info.key], raw_objects, frozen_at)
            if item.record.record_id in global_record_ids:
                raise source.ValidationError("invalid_or_duplicate_record_identifier")
            quarantine = exclude_station_mismatch_records(
                [item.record], protocol, timezone_kst=KST
            )
            if quarantine.excluded_records:
                station_mismatch_records += quarantine.excluded_records
                station_mismatch_observations += quarantine.excluded_observations
                quarantined_record_observations += len(item.record.observations)
                continue
            pairs = [
                (observation.private_token, observation.vehicle_hmac)
                for observation in item.record.observations
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
            global_record_ids.add(item.record.record_id)
            accepted_records.append(info)
            if item.response_key is not None:
                response_info = raw_info_by_key.get(item.response_key)
                if response_info is None or item.response_key in accepted_raw:
                    raise source.ValidationError("accepted_response_bijection_failure")
                accepted_raw[item.response_key] = response_info
            strategies[item.record.strategy_version] += 1
            outcomes[item.record.semantic_outcome] += 1
            observations += len(item.record.observations)
            seat_missing += item.record.seat_missing_rows
            repaired_crossings += int(item.repaired_partition_crossing)
        except source.ValidationError as error:
            invalid[error.code] += 1

    accepted_inventory = tuple((*accepted_records, *accepted_raw.values()))
    day_result = {
        "date": day,
        "freeze_at_utc": frozen_at.isoformat(),
        "inventory": {
            "record_documents": len(record_inventory),
            "raw_documents": len(raw_inventory),
            "bytes": sum(item.size for item in (*record_inventory, *raw_inventory)),
            "sha256": source.manifest_sha256((*record_inventory, *raw_inventory)),
        },
        "accepted": {
            "record_documents": len(accepted_records),
            "raw_documents": len(accepted_raw),
            "raw_less_records": len(accepted_records) - len(accepted_raw),
            "observations": observations,
            "seat_missing_rows": seat_missing,
            "sha256": source.manifest_sha256(accepted_inventory),
        },
        "invalid_records_by_code": dict(sorted(invalid.items())),
        "repaired_partition_crossings": repaired_crossings,
        "station_mismatch_records": station_mismatch_records,
        "station_mismatch_observations": station_mismatch_observations,
        "quarantined_record_observations": quarantined_record_observations,
        "strategies": dict(sorted(strategies.items())),
        "semantic_outcomes": dict(sorted(outcomes.items())),
        "unaccepted_raw_documents": len(raw_inventory) - len(accepted_raw),
    }
    del record_objects, raw_objects
    return day_result, accepted_inventory


def main() -> int:
    args = parse_arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output must not exist")
    protocol = load_protocol(args.protocol)
    days = calendar_days(args.from_date, args.through_date)
    client = source._s3_client(args.region)
    started = time.monotonic()
    global_record_ids: set[str] = set()
    token_to_hmac: dict[str, str] = {}
    hmac_to_token: dict[str, str] = {}
    daily = []
    accepted_inventories: list[tuple[source.ObjectInfo, ...]] = []
    for day in days:
        result, accepted = validate_day(
            client,
            args.bucket,
            protocol,
            day,
            args.workers,
            global_record_ids,
            token_to_hmac,
            hmac_to_token,
        )
        daily.append(result)
        accepted_inventories.append(accepted)
        print(
            json.dumps(
                {
                    "date": day,
                    "accepted_records": result["accepted"]["record_documents"],
                    "invalid_records": sum(result["invalid_records_by_code"].values()),
                    "status": "audited",
                },
                sort_keys=True,
            ),
            flush=True,
        )
    all_accepted = tuple(item for one_day in accepted_inventories for item in one_day)
    receipt = {
        "schema_version": "v4-1-source-audit-v1",
        "classification": (
            "V4_1_COMPATIBLE_BACKFILLED_SOURCE_CANDIDATE"
            if len(days) >= 30
            else f"V4_1_SOURCE_CANDIDATE_NOT_RELEASE_QUALIFIED_{len(days)}D"
        ),
        "observed_at_utc": datetime.now(UTC).isoformat(),
        "source": {
            "from_date": days[0],
            "through_date": days[-1],
            "completed_dates": len(days),
            "routes": list(source.ROUTES),
            "freeze_time_kst": "00:15:00",
            "inventory_algorithm": source.OBJECT_INVENTORY_ALGORITHM,
            "accepted_manifest_sha256": source.manifest_sha256(all_accepted),
        },
        "totals": {
            "inventory_record_documents": sum(item["inventory"]["record_documents"] for item in daily),
            "inventory_raw_documents": sum(item["inventory"]["raw_documents"] for item in daily),
            "inventory_bytes": sum(item["inventory"]["bytes"] for item in daily),
            "accepted_record_documents": sum(item["accepted"]["record_documents"] for item in daily),
            "accepted_raw_documents": sum(item["accepted"]["raw_documents"] for item in daily),
            "accepted_raw_less_records": sum(item["accepted"]["raw_less_records"] for item in daily),
            "accepted_observations": sum(item["accepted"]["observations"] for item in daily),
            "seat_missing_rows": sum(item["accepted"]["seat_missing_rows"] for item in daily),
            "invalid_records": sum(sum(item["invalid_records_by_code"].values()) for item in daily),
            "repaired_partition_crossings": sum(item["repaired_partition_crossings"] for item in daily),
            "station_mismatch_records": sum(item["station_mismatch_records"] for item in daily),
            "station_mismatch_observations": sum(item["station_mismatch_observations"] for item in daily),
            "quarantined_record_observations": sum(
                item["quarantined_record_observations"] for item in daily
            ),
            "unaccepted_raw_documents": sum(item["unaccepted_raw_documents"] for item in daily),
        },
        "daily": daily,
        "privacy": {
            "row_level_material_persisted": False,
            "object_keys_emitted": False,
            "vehicle_hmacs_emitted": False,
            "original_vehicle_ids_emitted": False,
            "plate_values_emitted": False,
            "secrets_emitted": False,
        },
        "runtime": {
            "duration_seconds": round(time.monotonic() - started, 3),
            "workers": args.workers,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    token_to_hmac.clear()
    hmac_to_token.clear()
    print(
        json.dumps(
            {
                "accepted_manifest_sha256": receipt["source"]["accepted_manifest_sha256"],
                "accepted_observations": receipt["totals"]["accepted_observations"],
                "status": "succeeded",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
