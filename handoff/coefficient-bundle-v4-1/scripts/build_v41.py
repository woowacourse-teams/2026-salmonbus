#!/usr/bin/env python3
"""Build a privacy-preserving v4-1 research candidate from frozen S3 source.

AWS calls are List/Get only. Raw source rows and vehicle identifiers remain in
memory; temporary files contain only de-identified derived model rows.
"""

from __future__ import annotations

import argparse
import bisect
import gzip
import hashlib
import heapq
import json
import math
import os
import resource
import re
import shutil
import sys
import tempfile
import time
from collections import Counter, defaultdict, deque
from dataclasses import dataclass
from datetime import date, datetime, time as clock_time, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo

import numpy as np
from safetensors.numpy import save_file

import audit_source
from current_public.data_pipeline import export_current as source
from current_public.evaluation.pipeline import load_protocol
from current_public.evaluation.station_quarantine import exclude_station_mismatch_records

from v41_model import (
    FEATURE_NAMES,
    HORIZONS,
    ROUTES,
    TENSOR_SPECS,
    ZERO_FEATURE_AXES,
    BlockFit,
    canonical_json,
    fit_block,
    fit_hurdle_anchor,
    prior_shift,
    score_batch,
    score_one,
    sha256_bytes,
    tensor_declarations,
)


UTC = timezone.utc
KST = ZoneInfo("Asia/Seoul")
G_GRID = (0, 10, 30, 60, 90, 120, 300, 600)
NORMAL_G_GRID = (0, 10, 30, 60, 90, 120)
S_GRID = (0, 60, 120, 300)
PRIMARY_S = 60
WINDOW_US = 30 * 60 * 1_000_000
LABEL_GAP_US = 90 * 1_000_000
MAX_WAIT_US = 2 * 60 * 60 * 1_000_000
TARGET_DEV_COMMIT = "d856d10819bf1d018ad43fa63714cc348f1fc643"
FEATURE_CONTRACT_VERSION = "observed-max-capacity-v1"
BACKFILL_POLICY_TEMPLATE = "s3-lag{g}-settle{s}-kst6h-chronological-v1"
ROUTE_REFERENCE_VERSION = "gbis-2026-08-19"
CHECKPOINT_DEFAULT = Path("/Users/idonghun/paseo/workspaces/2026-salmonbus/checkpoint.sh")

SETTLED = 0
SEAT_MISSING = 1
SKIPPED = 2
LOST = 3
PENDING = 4
STATE_NAMES = ("SETTLED", "SEAT_MISSING", "SKIPPED", "LOST", "PENDING")

BASE_DTYPE = np.dtype(
    [
        ("prediction_us", "<i8"),
        ("arrival_us", "<i8"),
        ("date_index", "u1"),
        ("target", "u1"),
        ("current", "u1"),
        ("capacity", "u1"),
        ("arrival", "u1"),
        ("time_slot", "u1"),
        ("eligible_mask", "u1"),
        ("base", "<f8", (28,)),
    ],
    align=False,
)


@dataclass(slots=True, frozen=True)
class Observation:
    vehicle: int
    passed: int
    seats: int
    crowd: int
    identity: int = -1


@dataclass(slots=True, frozen=True)
class PendingBatch:
    observed_us: int
    order_key: str
    open_training_predictions: bool
    open_seed_predictions: bool
    observations: tuple[Observation, ...]


@dataclass(slots=True, frozen=True)
class Batch:
    observed_us: int
    open_training_predictions: bool
    open_seed_predictions: bool
    observations: tuple[Observation, ...]


@dataclass(slots=True, frozen=True)
class Candidate:
    observed_us: int
    identity: int
    passed: int
    seats: int


@dataclass(slots=True, frozen=True)
class LabelOutcome:
    state: int
    identity: int = -1
    seats: int = -1
    observed_us: int = -1


@dataclass(slots=True)
class ScenarioProfiles:
    name: str
    guard_g: int
    settlement_s: int
    fill: dict[str, np.ndarray]
    net_segment: dict[str, np.ndarray]
    filled: dict[str, np.ndarray]
    revisions: dict[str, np.ndarray]
    summary: dict[str, Any]


@dataclass(slots=True)
class RouteReplayMaterial:
    vehicle_count: int
    capacity_changes: list[tuple[int, int, int]]
    h1_rows: np.ndarray
    replay_counts: dict[str, int]


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--from-date", required=True)
    parser.add_argument("--through-date", required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--source-audit", type=Path, required=True)
    parser.add_argument("--label-watermark-audit", type=Path, required=True)
    parser.add_argument("--catchup-audit", type=Path, required=True)
    parser.add_argument("--route-catchup-audit", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--checkpoint-script", type=Path, default=CHECKPOINT_DEFAULT)
    return parser.parse_args()


def checkpoint(script: Path, message: str) -> None:
    if not script.is_file():
        return
    import subprocess

    subprocess.run([str(script), message], check=True, stdout=subprocess.DEVNULL)


def to_us(value: datetime) -> int:
    value = value.astimezone(UTC)
    epoch = datetime(1970, 1, 1, tzinfo=UTC)
    delta = value - epoch
    return (delta.days * 86400 + delta.seconds) * 1_000_000 + delta.microseconds


def from_us(value: int) -> datetime:
    return datetime(1970, 1, 1, tzinfo=UTC) + timedelta(microseconds=int(value))


def utc_text_from_us(value: int) -> str:
    return from_us(value).isoformat(timespec="microseconds").replace("+00:00", "Z")


def safe_write_json(path: Path, value: object, *, canonical: bool = False) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f".{path.name}.tmp")
    payload = canonical_json(value) if canonical else (
        json.dumps(value, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")
    temporary.write_bytes(payload)
    os.replace(temporary, path)


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def peak_rss_mib() -> float:
    peak = float(resource.getrusage(resource.RUSAGE_SELF).ru_maxrss)
    divisor = 1024.0 * 1024.0 if sys.platform == "darwin" else 1024.0
    return round(peak / divisor, 3)


def route_rosters(protocol: Mapping[str, Any]) -> tuple[dict[str, dict[str, Any]], str]:
    versions = protocol["route_reference"]["versions"]
    version = next(
        item for item in versions if item["route_reference_version_id"] == ROUTE_REFERENCE_VERSION
    )
    prefixes = tuple(protocol["route_reference"]["non_boarding_station_id_prefixes"])
    rosters: dict[str, dict[str, Any]] = {}
    for route in ROUTES:
        stations = {int(key): str(value) for key, value in version["routes"][route]["stations"].items()}
        rosters[route] = {
            "stations": stations,
            "boarding": tuple(
                order
                for order, station_id in sorted(stations.items())
                if not station_id.startswith(prefixes)
            ),
            "largest": max(stations),
            "routeId": str(version["routes"][route]["route_id"]),
            "turnStationSeq": int(version["routes"][route]["turn_station_seq"]),
        }
    canonical_reference = {
        "effective_from": version["effective_from"],
        "effective_through": version["effective_through"],
        "route_reference_version_id": version["route_reference_version_id"],
        "routes": {
            route: {
                "route_id": version["routes"][route]["route_id"],
                "turn_station_seq": version["routes"][route]["turn_station_seq"],
                "stations": {
                    str(index): rosters[route]["stations"][index]
                    for index in range(1, int(rosters[route]["largest"]) + 1)
                },
            }
            for route in ROUTES
        },
    }
    return rosters, sha256_bytes(canonical_json(canonical_reference))


def targets_by_passed(roster: Mapping[str, Any]) -> dict[int, tuple[tuple[int, int], ...]]:
    result: dict[int, tuple[tuple[int, int], ...]] = {}
    for passed in range(0, int(roster["largest"]) + 1):
        result[passed] = tuple(
            (target, target - passed)
            for target in roster["boarding"]
            if 1 <= target - passed <= 12
        )
    return result


def _identity_is_consistent(
    observations: Sequence[source.BusObservation],
    token_to_hmac: dict[str, str],
    hmac_to_token: dict[str, str],
) -> bool:
    for observation in observations:
        token = observation.private_token
        hmac_value = observation.vehicle_hmac
        if hmac_value is None:
            return False
        if token_to_hmac.get(token, hmac_value) != hmac_value:
            return False
        if hmac_to_token.get(hmac_value, token) != token:
            return False
    return True


def load_source(
    args: argparse.Namespace,
    protocol: Mapping[str, Any],
    expected_audit: Mapping[str, Any],
    expected_watermark: Mapping[str, Any],
    expected_catchup: Mapping[str, Any],
    expected_route_catchup: Mapping[str, Any] | None = None,
    *,
    seed_only: bool = False,
) -> tuple[dict[str, list[Batch]], dict[str, int], dict[str, Any]]:
    client = source._s3_client(args.region)
    days = audit_source.calendar_days(args.from_date, args.through_date)
    global_record_ids: set[str] = set()
    token_to_hmac: dict[str, str] = {}
    hmac_to_token: dict[str, str] = {}
    vehicle_axes: dict[str, dict[str, int]] = {route: {} for route in ROUTES}
    pending: dict[str, list[PendingBatch]] = {route: [] for route in ROUTES}
    all_accepted_inventory: list[source.ObjectInfo] = []
    daily: list[dict[str, Any]] = []
    totals = Counter()

    for day in days:
        frozen_at = audit_source.freeze_at(day)
        records: list[source.ObjectInfo] = []
        raw: list[source.ObjectInfo] = []
        for route in ROUTES:
            records.extend(
                source.list_frozen_objects(
                    client, args.bucket, f"records/route={route}/dt={day}/", frozen_at
                )
            )
            raw.extend(
                source.list_frozen_objects(
                    client, args.bucket, f"raw/route={route}/dt={day}/", frozen_at
                )
            )
        records.sort(key=lambda item: item.key)
        raw.sort(key=lambda item: item.key)
        record_objects = source.read_frozen_objects(client, args.bucket, records, args.workers)
        raw_objects = source.read_frozen_objects(client, args.bucket, raw, args.workers)
        raw_info = {item.key: item for item in raw}
        accepted_record: list[source.ObjectInfo] = []
        accepted_raw: dict[str, source.ObjectInfo] = {}
        invalid = Counter()
        day_counts = Counter()

        for info in records:
            try:
                validated = audit_source.validate_record(
                    record_objects[info.key], raw_objects, frozen_at
                )
                record = validated.record
                if record.record_id in global_record_ids:
                    raise source.ValidationError("invalid_or_duplicate_record_identifier")
                quarantine = exclude_station_mismatch_records(
                    [record], protocol, timezone_kst=KST
                )
                if quarantine.excluded_records:
                    day_counts["station_mismatch_records"] += quarantine.excluded_records
                    day_counts["station_mismatch_observations"] += quarantine.excluded_observations
                    day_counts["quarantined_record_observations"] += len(record.observations)
                    # Preserve an empty structural batch so trajectories cannot
                    # connect across an untrusted quarantined response.
                    pending[record.route].append(
                        PendingBatch(
                            to_us(record.received_at), info.key, False, False, tuple()
                        )
                    )
                    day_counts["quarantine_break_batches"] += 1
                    continue
                if not _identity_is_consistent(record.observations, token_to_hmac, hmac_to_token):
                    raise source.ValidationError("vehicle_hmac_identity_conflict")
                for observation in record.observations:
                    assert observation.vehicle_hmac is not None
                    token_to_hmac[observation.private_token] = observation.vehicle_hmac
                    hmac_to_token[observation.vehicle_hmac] = observation.private_token
                global_record_ids.add(record.record_id)
                accepted_record.append(info)
                if validated.response_key is not None:
                    response_info = raw_info.get(validated.response_key)
                    if response_info is None or validated.response_key in accepted_raw:
                        raise source.ValidationError("accepted_response_bijection_failure")
                    accepted_raw[validated.response_key] = response_info

                normalized: list[Observation] = []
                if record.semantic_outcome != "failed":
                    for observation in record.observations:
                        if observation.state not in (0, 1, 2) or observation.station_id is None:
                            day_counts["dev_excluded_rows"] += 1
                            continue
                        # The approved continuity identity is the exact private
                        # source vehicle ID. HMAC is verified above but is not the
                        # journey key and never leaves memory.
                        axis = vehicle_axes[record.route].setdefault(
                            observation.private_token, len(vehicle_axes[record.route])
                        )
                        seats = (
                            int(observation.seats)
                            if observation.seats is not None
                            and float(observation.seats).is_integer()
                            else -1
                        )
                        if seats > 70:
                            raise source.ValidationError("seat_out_of_v4_1_grid")
                        crowd = (
                            int(observation.crowded)
                            if observation.crowded in (1, 2, 3, 4)
                            else -1
                        )
                        normalized.append(
                            Observation(axis, int(observation.last_passed), seats, crowd)
                        )
                successful = record.semantic_outcome != "failed"
                response_date = record.received_at.astimezone(KST).date().isoformat()
                opens_training = successful and response_date in days and not seed_only
                if successful and not opens_training:
                    day_counts["response_date_seed_only_batches"] += 1
                pending[record.route].append(
                    PendingBatch(
                        to_us(record.received_at),
                        info.key,
                        opens_training,
                        successful,
                        tuple(normalized),
                    )
                )
                day_counts["accepted_records"] += 1
                day_counts["accepted_raw"] += int(validated.response_key is not None)
                day_counts["normalized_observations"] += len(normalized)
                day_counts["source_observations"] += len(record.observations)
                day_counts["repaired_partition_crossings"] += int(
                    validated.repaired_partition_crossing
                )
            except source.ValidationError as error:
                invalid[error.code] += 1

        accepted_inventory = [*accepted_record, *accepted_raw.values()]
        all_accepted_inventory.extend(accepted_inventory)
        day_row = {
            "date": day,
            **dict(sorted(day_counts.items())),
            "invalidRecordsByCode": dict(sorted(invalid.items())),
            "acceptedManifestSha256": source.manifest_sha256(accepted_inventory),
        }
        daily.append(day_row)
        totals.update(day_counts)
        totals["invalid_records"] += sum(invalid.values())
        print(
            json.dumps(
                {
                    "date": day,
                    "normalizedObservations": day_counts["normalized_observations"],
                    "status": "loaded",
                },
                sort_keys=True,
            ),
            flush=True,
        )
        del record_objects, raw_objects

    # Read the next KST date through the strict source/target authority boundary.
    # It closes prior labels and extends the cell seed, but never becomes a
    # coefficient-training or quality-evaluation prediction date.
    route_refresh = expected_route_catchup is not None
    catchup_authority = expected_route_catchup if route_refresh else expected_catchup
    watermark_date = str(catchup_authority["partitionDateKst"])
    watermark_at = datetime.fromisoformat(
        str(
            catchup_authority[
                "snapshotAtUtc" if route_refresh else "snapshotObservedAtUtc"
            ]
        ).replace("Z", "+00:00")
    ).astimezone(UTC)
    if route_refresh:
        assert expected_route_catchup is not None
        if not all(
            expected_route_catchup["routes"][route][
                "membershipInvariantAcrossBoundaryBracket"
            ]
            for route in ROUTES
        ):
            raise RuntimeError("route_boundary_bracket_changes_catchup_membership")
        target_authority_by_route = {
            route: datetime.fromisoformat(
                str(expected_route_catchup["routes"][route]["boundaryLowerUtc"]).replace(
                    "Z", "+00:00"
                )
            ).astimezone(UTC)
            for route in ROUTES
        }
    else:
        target_authority = datetime.fromisoformat(
            str(expected_catchup["targetAuthorityFromUtcInclusive"]).replace(
                "Z", "+00:00"
            )
        ).astimezone(UTC)
        target_authority_by_route = {route: target_authority for route in ROUTES}
    watermark_records: list[source.ObjectInfo] = []
    watermark_raw: list[source.ObjectInfo] = []
    for route in ROUTES:
        watermark_records.extend(
            source.list_frozen_objects(
                client,
                args.bucket,
                f"records/route={route}/dt={watermark_date}/",
                watermark_at,
            )
        )
        watermark_raw.extend(
            source.list_frozen_objects(
                client,
                args.bucket,
                f"raw/route={route}/dt={watermark_date}/",
                watermark_at,
            )
        )
    watermark_records.sort(key=lambda item: item.key)
    watermark_raw.sort(key=lambda item: item.key)
    watermark_record_objects = source.read_frozen_objects(
        client, args.bucket, watermark_records, args.workers
    )
    watermark_raw_objects = source.read_frozen_objects(
        client, args.bucket, watermark_raw, args.workers
    )
    watermark_raw_info = {item.key: item for item in watermark_raw}
    watermark_accepted_records: list[source.ObjectInfo] = []
    watermark_accepted_raw: dict[str, source.ObjectInfo] = {}
    watermark_records_by_route: dict[str, list[source.ObjectInfo]] = {
        route: [] for route in ROUTES
    }
    watermark_raw_by_route: dict[str, dict[str, source.ObjectInfo]] = {
        route: {} for route in ROUTES
    }
    watermark_counts = Counter()
    watermark_counts_by_route = {route: Counter() for route in ROUTES}
    watermark_invalid = Counter()
    for info in watermark_records:
        try:
            validated = audit_source.validate_record(
                watermark_record_objects[info.key], watermark_raw_objects, watermark_at
            )
            record = validated.record
            if record.received_at >= target_authority_by_route[record.route]:
                continue
            if record.record_id in global_record_ids:
                raise source.ValidationError("invalid_or_duplicate_record_identifier")
            quarantine = exclude_station_mismatch_records(
                [record], protocol, timezone_kst=KST
            )
            if quarantine.excluded_records:
                watermark_counts["station_mismatch_records"] += quarantine.excluded_records
                watermark_counts["station_mismatch_observations"] += quarantine.excluded_observations
                watermark_counts["quarantined_record_observations"] += len(record.observations)
                watermark_counts_by_route[record.route]["station_mismatch_records"] += 1
                watermark_counts_by_route[record.route][
                    "quarantined_record_observations"
                ] += len(record.observations)
                pending[record.route].append(
                    PendingBatch(
                        to_us(record.received_at), info.key, False, False, tuple()
                    )
                )
                watermark_counts["quarantine_break_batches"] += 1
                continue
            if not _identity_is_consistent(
                record.observations, token_to_hmac, hmac_to_token
            ):
                raise source.ValidationError("vehicle_hmac_identity_conflict")
            for observation in record.observations:
                assert observation.vehicle_hmac is not None
                token_to_hmac[observation.private_token] = observation.vehicle_hmac
                hmac_to_token[observation.vehicle_hmac] = observation.private_token
            global_record_ids.add(record.record_id)
            watermark_accepted_records.append(info)
            watermark_records_by_route[record.route].append(info)
            if validated.response_key is not None:
                response_info = watermark_raw_info.get(validated.response_key)
                if response_info is None or validated.response_key in watermark_accepted_raw:
                    raise source.ValidationError("accepted_response_bijection_failure")
                watermark_accepted_raw[validated.response_key] = response_info
                watermark_raw_by_route[record.route][validated.response_key] = response_info

            normalized: list[Observation] = []
            if record.semantic_outcome != "failed":
                for observation in record.observations:
                    if observation.state not in (0, 1, 2) or observation.station_id is None:
                        watermark_counts["dev_excluded_rows"] += 1
                        continue
                    axis = vehicle_axes[record.route].setdefault(
                        observation.private_token, len(vehicle_axes[record.route])
                    )
                    seats = (
                        int(observation.seats)
                        if observation.seats is not None
                        and float(observation.seats).is_integer()
                        else -1
                    )
                    if seats > 70:
                        raise source.ValidationError("seat_out_of_v4_1_grid")
                    crowd = (
                        int(observation.crowded)
                        if observation.crowded in (1, 2, 3, 4)
                        else -1
                    )
                    normalized.append(
                        Observation(axis, int(observation.last_passed), seats, crowd)
                    )
            pending[record.route].append(
                PendingBatch(
                    to_us(record.received_at),
                    info.key,
                    False,
                    record.semantic_outcome != "failed",
                    tuple(normalized),
                )
            )
            watermark_counts["accepted_records"] += 1
            watermark_counts["accepted_raw"] += int(validated.response_key is not None)
            watermark_counts["normalized_observations"] += len(normalized)
            watermark_counts["source_observations"] += len(record.observations)
            watermark_counts_by_route[record.route]["source_observations"] += len(
                record.observations
            )
            watermark_counts["repaired_partition_crossings"] += int(
                validated.repaired_partition_crossing
            )
        except source.ValidationError as error:
            watermark_invalid[error.code] += 1
    watermark_accepted_inventory = [
        *watermark_accepted_records,
        *watermark_accepted_raw.values(),
    ]
    measured_watermark_digest = source.manifest_sha256(watermark_accepted_inventory)
    route_catchup_receipt: dict[str, Any] = {}
    if route_refresh:
        assert expected_route_catchup is not None
        for route in ROUTES:
            expected_route = expected_route_catchup["routes"][route]["cohorts"]["catchUp"]
            inventory = [
                *watermark_records_by_route[route],
                *watermark_raw_by_route[route].values(),
            ]
            measured_route_digest = source.manifest_sha256(inventory)
            if (
                measured_route_digest != expected_route["acceptedManifestSha256"]
                or len(watermark_records_by_route[route])
                != int(expected_route["acceptedRecordDocuments"])
                or len(watermark_raw_by_route[route])
                != int(expected_route["acceptedRawDocuments"])
                or watermark_counts_by_route[route]["source_observations"]
                != int(expected_route["acceptedObservations"])
                or watermark_counts_by_route[route]["station_mismatch_records"]
                != int(expected_route["quarantinedRecords"])
                or watermark_counts_by_route[route]["quarantined_record_observations"]
                != int(expected_route["quarantinedObservationRows"])
            ):
                raise RuntimeError(f"route_catchup_audit_mismatch:{route}")
            route_catchup_receipt[route] = {
                "targetAuthorityFromUtcInclusive": expected_route_catchup["routes"][route][
                    "boundaryLowerUtc"
                ],
                "acceptedManifestSha256": measured_route_digest,
                "acceptedRecordDocuments": len(watermark_records_by_route[route]),
                "acceptedRawDocuments": len(watermark_raw_by_route[route]),
                "acceptedObservations": int(expected_route["acceptedObservations"]),
            }
        expected_catchup_counts = {
            "acceptedRecordDocuments": sum(
                item["acceptedRecordDocuments"] for item in route_catchup_receipt.values()
            ),
            "acceptedRawDocuments": sum(
                item["acceptedRawDocuments"] for item in route_catchup_receipt.values()
            ),
            "acceptedObservations": sum(
                item["acceptedObservations"] for item in route_catchup_receipt.values()
            ),
            "quarantinedRecords": sum(
                int(expected_route_catchup["routes"][route]["cohorts"]["catchUp"]["quarantinedRecords"])
                for route in ROUTES
            ),
            "quarantinedObservationRows": sum(
                int(expected_route_catchup["routes"][route]["cohorts"]["catchUp"]["quarantinedObservationRows"])
                for route in ROUTES
            ),
        }
    else:
        expected_catchup_counts = expected_catchup["cohorts"]["catchUp"]
        if measured_watermark_digest != expected_catchup_counts["acceptedManifestSha256"]:
            raise RuntimeError("catchup_closure_changed_after_audit")
    if (
        len(watermark_accepted_records) != int(expected_catchup_counts["acceptedRecordDocuments"])
        or len(watermark_accepted_raw) != int(expected_catchup_counts["acceptedRawDocuments"])
        or watermark_counts["source_observations"]
        != int(expected_catchup_counts["acceptedObservations"])
        or watermark_counts["station_mismatch_records"]
        != int(expected_catchup_counts["quarantinedRecords"])
        or watermark_counts["quarantined_record_observations"]
        != int(expected_catchup_counts["quarantinedObservationRows"])
        or sum(watermark_invalid.values())
        != sum(int(value) for value in expected_watermark["invalidRecordsByCode"].values())
    ):
        raise RuntimeError("catchup_audit_count_mismatch")
    del watermark_record_objects, watermark_raw_objects

    measured_digest = source.manifest_sha256(all_accepted_inventory)
    expected_digest = expected_audit["source"]["accepted_manifest_sha256"]
    if measured_digest != expected_digest:
        raise RuntimeError("source_closure_changed_after_audit")
    expected_totals = expected_audit["totals"]
    comparisons = {
        "accepted_record_documents": totals["accepted_records"],
        "accepted_raw_documents": totals["accepted_raw"],
        "accepted_observations": totals["source_observations"],
        "invalid_records": totals["invalid_records"],
        "repaired_partition_crossings": totals["repaired_partition_crossings"],
        "station_mismatch_records": totals["station_mismatch_records"],
        "station_mismatch_observations": totals["station_mismatch_observations"],
    }
    for key, measured in comparisons.items():
        if int(expected_totals[key]) != int(measured):
            raise RuntimeError(f"source_audit_count_mismatch:{key}")

    compact: dict[str, list[Batch]] = {}
    tie_summary: dict[str, Any] = {}
    for route in ROUTES:
        ordered = sorted(pending[route], key=lambda item: (item.observed_us, item.order_key))
        tied_groups = Counter(item.observed_us for item in ordered)
        tied_sizes = [count for count in tied_groups.values() if count > 1]
        identity = 0
        batches: list[Batch] = []
        for item in ordered:
            observations = []
            for observation in item.observations:
                observations.append(
                    Observation(
                        observation.vehicle,
                        observation.passed,
                        observation.seats,
                        observation.crowd,
                        identity,
                    )
                )
                identity += 1
            batches.append(
                Batch(
                    item.observed_us,
                    item.open_training_predictions,
                    item.open_seed_predictions,
                    tuple(observations),
                )
            )
        compact[route] = batches
        tie_summary[route] = {
            "sameTimestampGroups": len(tied_sizes),
            "batchesInTies": int(sum(tied_sizes)),
            "maximumTieSize": int(max(tied_sizes, default=1)),
            "s3KeyOrderSensitivityRequired": bool(tied_sizes),
        }

    pending.clear()
    token_to_hmac.clear()
    hmac_to_token.clear()
    global_record_ids.clear()
    vehicle_counts = {route: len(vehicle_axes[route]) for route in ROUTES}
    vehicle_axes.clear()
    receipt = {
        "schemaVersion": "v4-1-private-source-load-v1",
        "acceptedManifestSha256": measured_digest,
        "dates": days,
        "daily": daily,
        "totals": dict(sorted(totals.items())),
        "vehicleAxisCardinality": vehicle_counts,
        "tieOrder": tie_summary,
        "privacy": {
            "rawRowsPersisted": False,
            "originalVehicleIdsPersisted": False,
            "vehicleHmacsPersisted": False,
            "plateValuesPersisted": False,
            "temporaryRowsAreDerivedAndDeidentified": True,
            "privateInProcessJourneyIdentity": "raw vehicle ID exact equality mapped to local integer",
            "sourceHmacUse": "integrity validation only, not journey identity",
        },
        "catchUpAuthority": {
            "dateKst": watermark_date,
            "targetAuthorityFromUtcInclusive": (
                None
                if route_refresh
                else expected_catchup["targetAuthorityFromUtcInclusive"]
            ),
            "routes": route_catchup_receipt,
            "acceptedManifestSha256": measured_watermark_digest,
            "acceptedRecordDocuments": len(watermark_accepted_records),
            "acceptedRawDocuments": len(watermark_accepted_raw),
            "acceptedObservations": int(watermark_counts["source_observations"]),
            "normalizedObservations": int(watermark_counts["normalized_observations"]),
            "quarantinedRecords": int(watermark_counts["station_mismatch_records"]),
            "stationMismatchRows": int(
                watermark_counts["station_mismatch_observations"]
            ),
            "quarantinedRecordObservations": int(
                watermark_counts["quarantined_record_observations"]
            ),
            "coefficientPredictionRowsOpened": 0,
            "seedStructuralPredictionsEnabled": True,
        },
        "labelOnlyWatermark": {
            "dateKst": expected_watermark["dateKst"],
            "watermarkAtUtc": expected_watermark["watermarkAtUtc"],
            "acceptedManifestSha256": expected_watermark["accepted"]["sha256"],
            "acceptedObservations": expected_watermark["accepted"]["observations"],
        },
    }
    return compact, vehicle_counts, receipt


def time_slot_at(observed_us: int) -> int:
    hour = from_us(observed_us).astimezone(KST).hour
    if 7 <= hour < 9:
        return 0
    if 17 <= hour < 20:
        return 1
    return 2


def label_date(observed_us: int) -> str:
    return from_us(observed_us).astimezone(KST).date().isoformat()


def build_future_index(batches: Sequence[Batch]) -> tuple[dict[int, list[Candidate]], dict[int, list[int]]]:
    future: dict[int, list[Candidate]] = defaultdict(list)
    for batch in batches:
        for observation in batch.observations:
            future[observation.vehicle].append(
                Candidate(
                    batch.observed_us,
                    observation.identity,
                    observation.passed,
                    observation.seats,
                )
            )
    times = {
        vehicle: [candidate.observed_us for candidate in candidates]
        for vehicle, candidates in future.items()
    }
    return dict(future), times


def resolve_targets(
    prediction_us: int,
    passed: int,
    targets: Sequence[tuple[int, int]],
    candidates: Sequence[Candidate],
    candidate_times: Sequence[int],
    cutoff_us: int,
    now_us: int,
) -> tuple[LabelOutcome, ...]:
    unresolved = {target for target, _ in targets}
    outcomes: dict[int, LabelOutcome] = {}
    previous_time = prediction_us
    previous_passed = passed
    start = bisect.bisect_right(candidate_times, cutoff_us)
    for candidate in candidates[start:]:
        if candidate.observed_us - previous_time > LABEL_GAP_US:
            for target in unresolved:
                outcomes[target] = LabelOutcome(LOST)
            unresolved.clear()
            break
        if candidate.passed < previous_passed:
            for target in unresolved:
                outcomes[target] = LabelOutcome(LOST)
            unresolved.clear()
            break
        skipped = [target for target in unresolved if target < candidate.passed]
        for target in skipped:
            outcomes[target] = LabelOutcome(SKIPPED)
            unresolved.remove(target)
        if candidate.passed in unresolved:
            outcomes[candidate.passed] = (
                LabelOutcome(SEAT_MISSING, candidate.identity, -1, candidate.observed_us)
                if candidate.seats < 0
                else LabelOutcome(
                    SETTLED, candidate.identity, candidate.seats, candidate.observed_us
                )
            )
            unresolved.remove(candidate.passed)
        previous_passed = candidate.passed
        previous_time = candidate.observed_us
        if not unresolved:
            break
    if unresolved:
        final_state = LOST if now_us - prediction_us > MAX_WAIT_US else PENDING
        for target in unresolved:
            outcomes[target] = LabelOutcome(final_state)
    return tuple(outcomes[target] for target, _ in targets)


def invariant_labels(
    prediction_us: int,
    passed: int,
    targets: Sequence[tuple[int, int]],
    candidates: Sequence[Candidate],
    candidate_times: Sequence[int],
    now_us: int,
) -> tuple[tuple[LabelOutcome, ...], list[int], list[int], list[int]]:
    cutoffs = {prediction_us}
    for guard in G_GRID:
        cutoffs.add(prediction_us + guard * 1_000_000)
    first = bisect.bisect_right(candidate_times, prediction_us)
    # At and above 90 seconds the Java gap rule makes the endpoint LOST. Candidate
    # crossings through that boundary are enough to account for every earlier
    # same-state event change; later crossings cannot restore a settled invariant.
    last = bisect.bisect_right(candidate_times, prediction_us + 90 * 1_000_000)
    for candidate_time in candidate_times[first:last]:
        cutoffs.add(candidate_time)
    resolved = {
        cutoff: resolve_targets(
            prediction_us,
            passed,
            targets,
            candidates,
            candidate_times,
            cutoff,
            now_us,
        )
        for cutoff in sorted(cutoffs)
    }
    baseline = resolved[prediction_us]
    masks = [0] * len(targets)
    state_changes = [0] * len(targets)
    event_changes = [0] * len(targets)
    for guard_index, guard in enumerate(G_GRID):
        upper = prediction_us + guard * 1_000_000
        points = [value for cutoff, value in resolved.items() if cutoff <= upper]
        for index, base in enumerate(baseline):
            if base.state != SETTLED:
                continue
            compared = [item[index] for item in points]
            if all(item == base for item in compared):
                masks[index] |= 1 << guard_index
            if any(item.state != base.state for item in compared):
                state_changes[index] |= 1 << guard_index
            if any(item.state == SETTLED and item.identity != base.identity for item in compared):
                event_changes[index] |= 1 << guard_index
    return baseline, masks, state_changes, event_changes


class BlockWriter:
    def __init__(self, path: Path, chunk_size: int = 20_000) -> None:
        self.path = path
        self.chunk_size = chunk_size
        self.buffer: list[tuple[Any, ...]] = []
        self.handle = path.open("wb")
        self.rows = 0

    def add(
        self,
        prediction_us: int,
        arrival_us: int,
        date_index: int,
        target: int,
        current: int,
        capacity: int,
        arrival: int,
        time_slot: int,
        eligible_mask: int,
        base: np.ndarray,
    ) -> None:
        self.buffer.append(
            (
                prediction_us,
                arrival_us,
                date_index,
                target,
                current,
                capacity,
                arrival,
                time_slot,
                eligible_mask,
                base.copy(),
            )
        )
        if len(self.buffer) >= self.chunk_size:
            self.flush()

    def flush(self) -> None:
        if not self.buffer:
            return
        values = np.empty(len(self.buffer), dtype=BASE_DTYPE)
        for index, item in enumerate(self.buffer):
            values[index] = item
        values.tofile(self.handle)
        self.rows += len(values)
        self.buffer.clear()

    def close(self) -> None:
        self.flush()
        self.handle.close()


def _base_features(
    observed_us: int,
    observation: Observation,
    capacity: int,
    chain: deque[tuple[int, int, int]],
    preceding: tuple[int, int] | None,
    target: int,
    largest_stop: int,
) -> np.ndarray:
    result = np.zeros(28, dtype=np.float64)
    result[0] = 1.0
    slot = time_slot_at(observed_us)
    result[1] = float(slot == 0)
    result[2] = float(slot == 1)
    seats = observation.seats
    result[4] = seats / capacity
    result[5] = float(seats == 0)
    result[6] = min(seats, 20) / 20.0
    if 1 <= observation.crowd <= 4:
        result[6 + observation.crowd] = 1.0
    result[11] = capacity / 68.0
    if len(chain) >= 2 and chain[-1][2] >= 0 and chain[-2][2] >= 0:
        result[12] = chain[-1][2] - chain[-2][2]
        result[13] = 0.0
    else:
        result[12] = 0.0
        result[13] = 1.0
    streak = 0
    counted_stop: int | None = None
    for _time, passed, historical_seats in reversed(chain):
        if historical_seats < 0 or historical_seats > 0:
            break
        if counted_stop is None or counted_stop != passed:
            streak += 1
            counted_stop = passed
    result[14] = float(streak)
    if preceding is None:
        result[15] = 0.0
        result[16] = 0.0
        result[17] = 1.0
    else:
        preceding_seats, _entered_us = preceding
        result[15] = float(preceding_seats == 0)
        result[16] = preceding_seats / capacity
        result[17] = 0.0
    result[19] = target / largest_stop
    return result


def _preceding_at(
    stop_windows: dict[int, dict[int, deque[tuple[int, int]]]],
    observation: Observation,
) -> tuple[int, int] | None:
    entries = stop_windows.get(observation.passed, {})
    own = entries.get(observation.vehicle)
    if not own:
        return None
    entered = own[0][0]
    nearest: tuple[int, int] | None = None
    for vehicle, values in entries.items():
        if vehicle == observation.vehicle or not values:
            continue
        other_time, other_seats = values[0]
        if other_time == entered:
            return None
        if other_time < entered and (nearest is None or other_time > nearest[1]):
            if other_seats < 0:
                nearest = (-1, other_time)
            else:
                nearest = (other_seats, other_time)
    if nearest is None or nearest[0] < 0:
        return None
    return nearest


def _flush_lag_row(
    store: dict[tuple[str, int, int, int], dict[str, Any]],
    route: str,
    horizon: int,
    date_index: int,
    time_slot: int,
    baseline: LabelOutcome,
    eligible_mask: int,
    state_mask: int,
    event_mask: int,
) -> None:
    key = (route, horizon, date_index, time_slot)
    row = store.setdefault(
        key,
        {
            "targetRows": 0,
            "baselineStates": [0] * len(STATE_NAMES),
            "eligible": [0] * len(G_GRID),
            "eligibleFull": [0] * len(G_GRID),
            "stateChanged": [0] * len(G_GRID),
            "arrivalEventChanged": [0] * len(G_GRID),
        },
    )
    row["targetRows"] += 1
    row["baselineStates"][baseline.state] += 1
    for index in range(len(G_GRID)):
        if eligible_mask & (1 << index):
            row["eligible"][index] += 1
            row["eligibleFull"][index] += int(baseline.seats == 0)
        if state_mask & (1 << index):
            row["stateChanged"][index] += 1
        if event_mask & (1 << index):
            row["arrivalEventChanged"][index] += 1


def materialize_route(
    route: str,
    batches: list[Batch],
    vehicle_count: int,
    roster: Mapping[str, Any],
    dates: Sequence[str],
    work: Path,
    lag_rows: dict[tuple[str, int, int, int], dict[str, Any]],
    now_us: int,
) -> RouteReplayMaterial:
    target_map = targets_by_passed(roster)
    future, future_times = build_future_index(batches)
    writers = {
        horizon: BlockWriter(work / f"base-{route}-h{horizon}.bin")
        for horizon in HORIZONS
    }
    chains: dict[int, tuple[int, deque[tuple[int, int, int]]]] = {}
    stop_windows: dict[int, dict[int, deque[tuple[int, int]]]] = defaultdict(dict)
    maximum = np.zeros(vehicle_count, dtype=np.int16)
    capacity_changes: list[tuple[int, int, int]] = []
    h1_buffer: list[tuple[int, ...]] = []
    replay_counts = Counter()
    date_axis = {value: index for index, value in enumerate(dates)}

    for batch_index, batch in enumerate(batches):
        cutoff = batch.observed_us - WINDOW_US
        for observation in batch.observations:
            if observation.seats > 0 and observation.seats > maximum[observation.vehicle]:
                maximum[observation.vehicle] = observation.seats
                capacity_changes.append(
                    (batch.observed_us, observation.vehicle, observation.seats)
                )

        current_chains: dict[int, deque[tuple[int, int, int]]] = {}
        for observation in batch.observations:
            previous_state = chains.get(observation.vehicle)
            chain: deque[tuple[int, int, int]]
            if previous_state is None or previous_state[0] != batch_index - 1:
                chain = deque()
            else:
                chain = previous_state[1]
                while chain and chain[0][0] <= cutoff:
                    chain.popleft()
                if chain:
                    moved = observation.passed - chain[-1][1]
                    if moved < 0 or moved > 1:
                        chain = deque()
            chain.append((batch.observed_us, observation.passed, observation.seats))
            current_chains[observation.vehicle] = chain

        relevant_stops = {observation.passed for observation in batch.observations}
        for passed in relevant_stops:
            by_vehicle = stop_windows[passed]
            for vehicle in list(by_vehicle):
                values = by_vehicle[vehicle]
                while values and values[0][0] <= cutoff:
                    values.popleft()
                if not values:
                    del by_vehicle[vehicle]
        for observation in batch.observations:
            values = stop_windows[observation.passed].setdefault(
                observation.vehicle, deque()
            )
            values.append((batch.observed_us, observation.seats))

        if batch.open_training_predictions or batch.open_seed_predictions:
            date_index: int | None = None
            if batch.open_training_predictions:
                prediction_date = label_date(batch.observed_us)
                if prediction_date not in date_axis:
                    raise RuntimeError("prediction_date_outside_frozen_axis")
                date_index = date_axis[prediction_date]
            slot = time_slot_at(batch.observed_us)
            for observation in batch.observations:
                capacity = int(maximum[observation.vehicle])
                if observation.seats < 0 or capacity <= 0:
                    continue
                targets = target_map.get(observation.passed, ())
                if not batch.open_training_predictions:
                    targets = tuple(item for item in targets if item[1] == 1)
                if not targets:
                    continue
                base_outcomes, masks, state_changes, event_changes = invariant_labels(
                    batch.observed_us,
                    observation.passed,
                    targets,
                    future.get(observation.vehicle, ()),
                    future_times.get(observation.vehicle, ()),
                    now_us,
                )
                preceding = _preceding_at(stop_windows, observation)
                chain = current_chains[observation.vehicle]
                for target_index, (target, horizon) in enumerate(targets):
                    outcome = base_outcomes[target_index]
                    eligible_mask = masks[target_index]
                    if batch.open_training_predictions:
                        assert date_index is not None
                        _flush_lag_row(
                            lag_rows,
                            route,
                            horizon,
                            date_index,
                            slot,
                            outcome,
                            eligible_mask,
                            state_changes[target_index],
                            event_changes[target_index],
                        )
                    else:
                        replay_counts["seedOnlyStructuralTargets"] += 1
                    if outcome.state != SETTLED or eligible_mask == 0:
                        continue
                    if batch.open_training_predictions:
                        assert date_index is not None
                        base = _base_features(
                            batch.observed_us,
                            observation,
                            capacity,
                            chain,
                            preceding,
                            target,
                            int(roster["largest"]),
                        )
                        writers[horizon].add(
                            batch.observed_us,
                            outcome.observed_us,
                            date_index,
                            target,
                            observation.seats,
                            capacity,
                            outcome.seats,
                            slot,
                            eligible_mask,
                            base,
                        )
                    if horizon == 1 and batch.open_seed_predictions:
                        if not batch.open_training_predictions:
                            replay_counts["seedOnlyInvariantSettledTargets"] += 1
                        arrival_local = from_us(outcome.observed_us).astimezone(KST)
                        hour_utc = outcome.observed_us // 3_600_000_000
                        h1_buffer.append(
                            (
                                batch.observed_us,
                                outcome.observed_us,
                                arrival_local.date().toordinal(),
                                hour_utc,
                                time_slot_at(outcome.observed_us),
                                target,
                                observation.seats,
                                outcome.seats,
                                observation.vehicle,
                                eligible_mask,
                            )
                        )
        for vehicle, chain in current_chains.items():
            chains[vehicle] = (batch_index, chain)

        if (batch_index + 1) % 10_000 == 0:
            print(
                json.dumps(
                    {
                        "route": route,
                        "batches": batch_index + 1,
                        "totalBatches": len(batches),
                        "status": "materializing",
                    },
                    sort_keys=True,
                ),
                flush=True,
            )

    for writer in writers.values():
        writer.close()
    h1_dtype = np.dtype(
        [
            ("prediction_us", "<i8"),
            ("arrival_us", "<i8"),
            ("arrival_day", "<i4"),
            ("arrival_hour_utc", "<i8"),
            ("arrival_slot", "u1"),
            ("target", "u1"),
            ("current", "u1"),
            ("arrival", "u1"),
            ("vehicle", "<i4"),
            ("eligible_mask", "u1"),
        ],
        align=False,
    )
    h1_rows = np.asarray(h1_buffer, dtype=h1_dtype)
    return RouteReplayMaterial(
        vehicle_count=vehicle_count,
        capacity_changes=capacity_changes,
        h1_rows=h1_rows,
        replay_counts=dict(sorted(replay_counts.items())),
    )


def generation_boundaries(first_day: str, through_us: int) -> np.ndarray:
    current = date.fromisoformat(first_day)
    final = from_us(through_us).astimezone(KST).date()
    values: list[int] = []
    while current <= final:
        for hour in (0, 6, 12, 18):
            candidate = to_us(datetime.combine(current, clock_time(hour), tzinfo=KST))
            if candidate <= through_us:
                values.append(candidate)
        current += timedelta(days=1)
    return np.asarray(values, dtype=np.int64)


def capacity_at_boundaries(
    boundaries: np.ndarray,
    vehicle_count: int,
    changes: Sequence[tuple[int, int, int]],
) -> np.ndarray:
    result = np.zeros((len(boundaries), vehicle_count), dtype=np.int16)
    current = np.zeros(vehicle_count, dtype=np.int16)
    ordered = sorted(changes)
    cursor = 0
    for boundary_index, boundary in enumerate(boundaries):
        while cursor < len(ordered) and ordered[cursor][0] <= int(boundary):
            _observed, vehicle, seats = ordered[cursor]
            current[vehicle] = max(current[vehicle], seats)
            cursor += 1
        result[boundary_index] = current
    return result


def empty_profile(largest: int) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    return (
        np.zeros((3, largest + 1), dtype=np.float64),
        np.zeros((3, largest + 1, 13), dtype=np.float64),
        np.ones((3, largest + 1), dtype=np.uint8),
    )


def cell_profile(
    rows: np.ndarray,
    capacities: np.ndarray,
    largest: int,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, dict[str, int]]:
    if not len(rows):
        fill, net, filled = empty_profile(largest)
        return fill, net, filled, {"cells": 0, "samples": 0, "days": 0}
    valid = capacities > 0
    rows = rows[valid]
    capacities = capacities[valid].astype(np.float64)
    if not len(rows):
        fill, net, filled = empty_profile(largest)
        return fill, net, filled, {"cells": 0, "samples": 0, "days": 0}
    day_values = np.unique(rows["arrival_day"])
    day_axis = np.searchsorted(day_values, rows["arrival_day"])
    key = (
        rows["arrival_slot"].astype(np.int64) * len(day_values) * (largest + 1)
        + day_axis.astype(np.int64) * (largest + 1)
        + rows["target"].astype(np.int64)
    )
    length = 3 * len(day_values) * (largest + 1)
    count = np.bincount(key, minlength=length).reshape(3, len(day_values), largest + 1)
    fill_total = np.bincount(
        key,
        weights=1.0 - rows["arrival"].astype(np.float64) / capacities,
        minlength=length,
    ).reshape(3, len(day_values), largest + 1)
    net_total = np.bincount(
        key,
        weights=rows["current"].astype(np.float64) - rows["arrival"].astype(np.float64),
        minlength=length,
    ).reshape(3, len(day_values), largest + 1)
    capacity_total = np.bincount(
        key, weights=capacities, minlength=length
    ).reshape(3, len(day_values), largest + 1)

    average_fill = np.full((3, largest + 1), np.nan, dtype=np.float64)
    average_net = np.full((3, largest + 1), np.nan, dtype=np.float64)
    cell_count = 0
    represented_days: set[int] = set()
    for slot in range(3):
        for target in range(1, largest + 1):
            represented = count[slot, :, target] > 0
            if not np.any(represented):
                continue
            represented_days.update(day_values[represented].tolist())
            average_fill[slot, target] = float(
                np.mean(
                    fill_total[slot, represented, target]
                    / count[slot, represented, target]
                )
            )
            average_net[slot, target] = float(
                np.mean(
                    net_total[slot, represented, target]
                    / capacity_total[slot, represented, target]
                )
            )
            cell_count += 1

    fill_lookup, net_segment, filled = empty_profile(largest)
    for slot in range(3):
        seen = np.isfinite(average_fill[slot])
        if not np.any(seen):
            continue
        fill_values = average_fill[slot, seen]
        net_values = average_net[slot, seen]
        fill_z = np.zeros(largest + 1, dtype=np.float64)
        net_z = np.zeros(largest + 1, dtype=np.float64)
        fill_z[seen] = (fill_values - float(np.mean(fill_values))) / (
            float(np.std(fill_values, ddof=0)) + 1e-9
        )
        net_z[seen] = (net_values - float(np.mean(net_values))) / (
            float(np.std(net_values, ddof=0)) + 1e-9
        )
        filled[slot, seen] = 0
        for target in range(1, largest + 1):
            if seen[target]:
                fill_lookup[slot, target] = fill_z[target]
            else:
                neighbours = [
                    other
                    for other in range(max(1, target - 4), min(largest, target + 4) + 1)
                    if other != target and seen[other]
                ]
                if neighbours:
                    weights = np.asarray(
                        [1.0 / ((other - target) ** 2) for other in neighbours],
                        dtype=np.float64,
                    )
                    fill_lookup[slot, target] = float(
                        np.dot(weights, fill_z[neighbours]) / np.sum(weights)
                    )
            for horizon in HORIZONS:
                window = [
                    other
                    for other in range(max(1, target - horizon + 1), target + 1)
                    if seen[other]
                ]
                if window:
                    net_segment[slot, target, horizon] = float(
                        np.sum(net_z[window]) / math.sqrt(len(window))
                    )
                else:
                    neighbours = [
                        other
                        for other in range(max(1, target - 4), min(largest, target + 4) + 1)
                        if other != target and seen[other]
                    ]
                    if neighbours:
                        weights = np.asarray(
                            [1.0 / ((other - target) ** 2) for other in neighbours],
                            dtype=np.float64,
                        )
                        net_segment[slot, target, horizon] = float(
                            np.dot(weights, net_z[neighbours]) / np.sum(weights)
                        )
    return fill_lookup, net_segment, filled, {
        "cells": cell_count,
        "samples": int(len(rows)),
        "days": len(represented_days),
    }


def build_profiles(
    name: str,
    guard_g: int,
    settlement_s: int,
    materials: Mapping[str, RouteReplayMaterial],
    rosters: Mapping[str, Mapping[str, Any]],
    boundaries: np.ndarray,
) -> ScenarioProfiles:
    guard_index = G_GRID.index(guard_g)
    fill_by_route: dict[str, np.ndarray] = {}
    net_by_route: dict[str, np.ndarray] = {}
    filled_by_route: dict[str, np.ndarray] = {}
    revisions_by_route: dict[str, np.ndarray] = {}
    summary: dict[str, Any] = {}
    for route in ROUTES:
        material = materials[route]
        largest = int(rosters[route]["largest"])
        capacities = capacity_at_boundaries(
            boundaries,
            material.vehicle_count,
            material.capacity_changes,
        )
        fills = np.zeros((len(boundaries), 3, largest + 1), dtype=np.float64)
        nets = np.zeros((len(boundaries), 3, largest + 1, 13), dtype=np.float64)
        filled = np.ones((len(boundaries), 3, largest + 1), dtype=np.uint8)
        revisions = np.zeros(len(boundaries), dtype=np.int16)
        route_summary = []
        revision = 0
        eligible = (material.h1_rows["eligible_mask"] & (1 << guard_index)) != 0
        for boundary_index, boundary in enumerate(boundaries):
            active = eligible & (
                material.h1_rows["arrival_us"] + settlement_s * 1_000_000 <= boundary
            )
            active_rows = material.h1_rows[active]
            if len(active_rows):
                revision += 1
                cap = capacities[boundary_index, active_rows["vehicle"]]
                one_fill, one_net, one_filled, stats = cell_profile(
                    active_rows, cap, largest
                )
                fills[boundary_index] = one_fill
                nets[boundary_index] = one_net
                filled[boundary_index] = one_filled
            else:
                stats = {"cells": 0, "samples": 0, "days": 0}
            revisions[boundary_index] = revision
            route_summary.append(
                {
                    "generationTime": utc_text_from_us(int(boundary)),
                    "revision": revision,
                    **stats,
                }
            )
        fill_by_route[route] = fills
        net_by_route[route] = nets
        filled_by_route[route] = filled
        revisions_by_route[route] = revisions
        summary[route] = {
            "generations": route_summary,
            "finalRevision": revision,
            "eligibleHorizonOneRows": int(np.sum(eligible)),
        }
    return ScenarioProfiles(
        name=name,
        guard_g=guard_g,
        settlement_s=settlement_s,
        fill=fill_by_route,
        net_segment=net_by_route,
        filled=filled_by_route,
        revisions=revisions_by_route,
        summary=summary,
    )


def block_memmap(path: Path) -> np.memmap:
    size = path.stat().st_size
    if size % BASE_DTYPE.itemsize:
        raise RuntimeError("corrupt_temporary_block")
    return np.memmap(path, dtype=BASE_DTYPE, mode="r", shape=(size // BASE_DTYPE.itemsize,))


def features_for(
    rows: np.ndarray,
    route: str,
    horizon: int,
    profiles: ScenarioProfiles,
    boundaries: np.ndarray,
) -> np.ndarray:
    matrix = np.zeros((len(rows), 31), dtype=np.float64)
    matrix[:, :28] = rows["base"]
    generation = np.searchsorted(boundaries, rows["prediction_us"], side="right") - 1
    generation = np.maximum(generation, 0)
    slots = rows["time_slot"].astype(np.int16)
    targets = rows["target"].astype(np.int16)
    matrix[:, 28] = profiles.fill[route][generation, slots, targets]
    matrix[:, 29] = profiles.net_segment[route][generation, slots, targets, horizon]
    matrix[:, 30] = profiles.filled[route][generation, slots, targets]
    if np.any(matrix[:, ZERO_FEATURE_AXES] != 0.0):
        raise RuntimeError("zero_feature_contract_breached")
    if np.any(~np.isfinite(matrix)):
        raise RuntimeError("non_finite_materialized_feature")
    return matrix


def lag_receipt_rows(
    lag_rows: Mapping[tuple[str, int, int, int], Mapping[str, Any]],
    dates: Sequence[str],
) -> tuple[list[dict[str, Any]], dict[int, dict[str, Any]]]:
    rows: list[dict[str, Any]] = []
    totals = {
        guard: {
            "targetRows": 0,
            "baselineSettledRows": 0,
            "eligibleRows": 0,
            "eligibleFullRows": 0,
            "stateChangedRows": 0,
            "arrivalEventChangedRows": 0,
        }
        for guard in G_GRID
    }
    for (route, horizon, date_index, time_slot), item in sorted(lag_rows.items()):
        row = {
            "route": route,
            "horizon": horizon,
            "date": dates[date_index],
            "timeSlot": ("MORNING", "EVENING", "OTHER")[time_slot],
            "targetRows": item["targetRows"],
            "baselineStates": {
                state: item["baselineStates"][index]
                for index, state in enumerate(STATE_NAMES)
            },
            "guards": [],
        }
        for guard_index, guard in enumerate(G_GRID):
            eligible = item["eligible"][guard_index]
            detail = {
                "gSeconds": guard,
                "eligibleRows": eligible,
                "eligibleFullRows": item["eligibleFull"][guard_index],
                "retentionOfTargets": eligible / item["targetRows"] if item["targetRows"] else 0.0,
                "stateChangedRows": item["stateChanged"][guard_index],
                "arrivalEventChangedRows": item["arrivalEventChanged"][guard_index],
            }
            row["guards"].append(detail)
            aggregate = totals[guard]
            aggregate["targetRows"] += item["targetRows"]
            aggregate["baselineSettledRows"] += item["baselineStates"][SETTLED]
            aggregate["eligibleRows"] += eligible
            aggregate["eligibleFullRows"] += item["eligibleFull"][guard_index]
            aggregate["stateChangedRows"] += item["stateChanged"][guard_index]
            aggregate["arrivalEventChangedRows"] += item["arrivalEventChanged"][guard_index]
        rows.append(row)
    for guard, aggregate in totals.items():
        aggregate["retentionOfTargets"] = (
            aggregate["eligibleRows"] / aggregate["targetRows"]
            if aggregate["targetRows"]
            else 0.0
        )
        aggregate["retentionOfBaselineSettled"] = (
            aggregate["eligibleRows"] / aggregate["baselineSettledRows"]
            if aggregate["baselineSettledRows"]
            else 0.0
        )
        aggregate["fullPrevalence"] = (
            aggregate["eligibleFullRows"] / aggregate["eligibleRows"]
            if aggregate["eligibleRows"]
            else None
        )
    return rows, totals


def select_guard(
    work: Path,
    dates: Sequence[str],
) -> tuple[int, dict[int, dict[str, Any]]]:
    gates: dict[int, dict[str, Any]] = {}
    for guard in G_GRID:
        bit = 1 << G_GRID.index(guard)
        failures = []
        block_counts = {}
        for route in ROUTES:
            for horizon in HORIZONS:
                rows = block_memmap(work / f"base-{route}-h{horizon}.bin")
                selected = rows[(rows["eligible_mask"] & bit) != 0]
                count = len(selected)
                dates_seen = len(np.unique(selected["date_index"])) if count else 0
                positive = int(np.sum(selected["arrival"] > 0)) if count else 0
                key = f"{route}:{horizon}"
                block_counts[key] = {
                    "rows": count,
                    "dates": dates_seen,
                    "positiveSeatRows": positive,
                }
                if count < 200 or dates_seen < 2 or positive == 0:
                    failures.append(key)
                del rows, selected
        gates[guard] = {
            "passesExistingBlockGate": not failures,
            "failedBlocks": failures,
            "blocks": block_counts,
        }
    viable = [guard for guard in NORMAL_G_GRID if gates[guard]["passesExistingBlockGate"]]
    if not viable:
        raise RuntimeError("no_generated_at_guard_passes_existing_block_gate")
    return max(viable), gates


def split_dates(dates: Sequence[str]) -> dict[str, Any]:
    count = len(dates)
    if count >= 30:
        evaluation_days = 7
        qualification = "PASS"
        policy = "canonical-7d-calibration-7d-holdout"
    else:
        if count < 12:
            raise RuntimeError("insufficient_dates_even_for_provisional_split")
        # Preserve at least nine development dates and split the remaining tail
        # evenly, capped at the canonical seven days. This is an explicit research
        # fallback, never a replacement for the N>=30 release gate.
        evaluation_days = min(7, (count - 9) // 2)
        if evaluation_days < 2:
            raise RuntimeError("insufficient_provisional_evaluation_dates")
        qualification = "FAIL_N_LT_30"
        policy = "provisional-preserve-9-development-even-tail"
    development_end = count - 2 * evaluation_days
    calibration_end = count - evaluation_days
    return {
        "completeDateCount": count,
        "requiredCompleteDateCount": 30,
        "releaseQualification": qualification,
        "policy": policy,
        "development": list(dates[:development_end]),
        "calibration": list(dates[development_end:calibration_end]),
        "holdout": list(dates[calibration_end:]),
        "developmentEndIndex": development_end,
        "calibrationEndIndex": calibration_end,
    }


def date_start_us(value: str) -> int:
    return to_us(datetime.combine(date.fromisoformat(value), clock_time(0), tzinfo=KST))


def bootstrap_index(
    work: Path,
    dates: Sequence[str],
    guard: int,
    settlement_s: int,
    development_end: int,
) -> int | None:
    bit = 1 << G_GRID.index(guard)
    for cutoff in range(1, development_end):
        available_at = date_start_us(dates[cutoff + 1]) if cutoff + 1 < len(dates) else 2**63 - 1
        passed = True
        for route in ROUTES:
            for horizon in HORIZONS:
                rows = block_memmap(work / f"base-{route}-h{horizon}.bin")
                selected = rows[
                    ((rows["eligible_mask"] & bit) != 0)
                    & (rows["date_index"] <= cutoff)
                    & (rows["arrival_us"] + settlement_s * 1_000_000 <= available_at)
                ]
                if (
                    len(selected) < 200
                    or len(np.unique(selected["date_index"])) < 2
                    or not np.any(selected["arrival"] > 0)
                ):
                    passed = False
                del rows, selected
                if not passed:
                    break
            if not passed:
                break
        if passed:
            return cutoff
    return None


def shifted_probabilities(
    raw: np.ndarray,
    rows: np.ndarray,
    settlement_s: int,
) -> np.ndarray:
    raw = np.asarray(raw, dtype=np.float64)
    if raw.shape != (len(rows),):
        raise ValueError("shifted_probability_shape")
    shifted = np.empty_like(raw)
    order = np.argsort(rows["prediction_us"], kind="stable")
    heap: list[tuple[int, int, int]] = []
    by_arrival_day: dict[int, list[float]] = defaultdict(lambda: [0.0, 0.0, 0.0])
    cursor = 0
    while cursor < len(order):
        prediction_us = int(rows["prediction_us"][order[cursor]])
        end = cursor + 1
        while end < len(order) and int(rows["prediction_us"][order[end]]) == prediction_us:
            end += 1
        while heap and heap[0][0] <= prediction_us:
            _available, arrival_day, row_index = heapq.heappop(heap)
            state = by_arrival_day[arrival_day]
            state[0] += 1.0
            state[1] += float(rows["arrival"][row_index] == 0)
            state[2] += float(raw[row_index])
        prediction_day = from_us(prediction_us).astimezone(KST).date().toordinal()
        state = by_arrival_day[prediction_day]
        for offset in range(cursor, end):
            row_index = int(order[offset])
            shifted[row_index] = prior_shift(
                float(raw[row_index]), int(state[0]), int(state[1]), float(state[2])
            )
        for offset in range(cursor, end):
            row_index = int(order[offset])
            arrival_day = from_us(int(rows["arrival_us"][row_index])).astimezone(KST).date().toordinal()
            heapq.heappush(
                heap,
                (
                    int(rows["arrival_us"][row_index]) + settlement_s * 1_000_000,
                    arrival_day,
                    row_index,
                ),
            )
        cursor = end
    return shifted


def calibration_diagnostic(probability: np.ndarray, labels: np.ndarray) -> dict[str, float | None]:
    p = np.clip(np.asarray(probability, dtype=np.float64), 1e-6, 1.0 - 1e-6)
    y = np.asarray(labels, dtype=np.float64)
    if len(y) < 3 or np.all(y == y[0]):
        return {"intercept": None, "slope": None}
    logit = np.log(p / (1.0 - p))
    matrix = np.column_stack((np.ones_like(logit), logit))
    try:
        coefficients, _ = fit_hurdle_for_diagnostic(matrix, y)
    except (ValueError, np.linalg.LinAlgError):
        return {"intercept": None, "slope": None}
    return {"intercept": float(coefficients[0]), "slope": float(coefficients[1])}


def fit_hurdle_for_diagnostic(matrix: np.ndarray, labels: np.ndarray) -> tuple[np.ndarray, int]:
    coefficients = np.zeros(matrix.shape[1], dtype=np.float64)
    used = 0
    for used in range(1, 61):
        z = np.clip(matrix @ coefficients, -30.0, 30.0)
        p = 1.0 / (1.0 + np.exp(-z))
        w = p * (1.0 - p) + 1e-9
        h = matrix.T @ (matrix * w[:, None])
        g = matrix.T @ (labels - p)
        step = np.linalg.lstsq(h, g, rcond=None)[0]
        coefficients += step
        if np.max(np.abs(step)) < 1e-10:
            break
    if not np.all(np.isfinite(coefficients)):
        raise ValueError("diagnostic_non_finite")
    return coefficients, used


def reliability_bins(probability: np.ndarray, labels: np.ndarray) -> list[dict[str, Any]]:
    order = np.argsort(probability, kind="stable")
    result = []
    for index, selected in enumerate(np.array_split(order, 10)):
        if not len(selected):
            continue
        result.append(
            {
                "bin": index + 1,
                "count": int(len(selected)),
                "predicted": float(np.mean(probability[selected])),
                "observed": float(np.mean(labels[selected])),
                "minimum": float(np.min(probability[selected])),
                "maximum": float(np.max(probability[selected])),
            }
        )
    return result


def metric_summary(
    raw: np.ndarray,
    shifted: np.ndarray,
    labels: np.ndarray,
    expected: np.ndarray,
    arrival: np.ndarray,
    crps: np.ndarray,
    *,
    pmf_sum_error: float,
    pmf_p0_error: float,
    pmf_minimum: float,
    pmf_maximum: float,
) -> dict[str, Any]:
    labels = np.asarray(labels, dtype=np.float64)
    raw = np.asarray(raw, dtype=np.float64)
    shifted = np.asarray(shifted, dtype=np.float64)
    eps = 1e-12
    log_loss = lambda p: float(
        -np.mean(labels * np.log(np.clip(p, eps, 1.0)) + (1.0 - labels) * np.log(np.clip(1.0 - p, eps, 1.0)))
    )
    return {
        "rows": int(len(labels)),
        "fullRows": int(np.sum(labels)),
        "fullPrevalence": float(np.mean(labels)),
        "raw": {
            "brier": float(np.mean((raw - labels) ** 2)),
            "logLoss": log_loss(raw),
            "calibrationInTheLarge": float(np.mean(raw) - np.mean(labels)),
            "diagnosticCalibration": calibration_diagnostic(raw, labels),
            "reliability": reliability_bins(raw, labels),
        },
        "shifted": {
            "brier": float(np.mean((shifted - labels) ** 2)),
            "logLoss": log_loss(shifted),
            "calibrationInTheLarge": float(np.mean(shifted) - np.mean(labels)),
            "diagnosticCalibration": calibration_diagnostic(shifted, labels),
            "reliability": reliability_bins(shifted, labels),
        },
        "expectedSeats": {
            "mae": float(np.mean(np.abs(expected - arrival))),
            "rmse": float(np.sqrt(np.mean((expected - arrival) ** 2))),
            "crps": float(np.mean(crps)),
        },
        "pmf": {
            "maximumSumError": float(pmf_sum_error),
            "maximumP0Error": float(pmf_p0_error),
            "minimumProbability": float(pmf_minimum),
            "maximumProbability": float(pmf_maximum),
        },
    }


def evaluate_fit(
    fit: BlockFit,
    matrix: np.ndarray,
    rows: np.ndarray,
    evaluation_mask: np.ndarray,
    settlement_s: int,
    chunk_size: int = 20_000,
) -> tuple[dict[str, Any], dict[str, np.ndarray]]:
    raw_all = 1.0 / (
        1.0
        + np.exp(-np.clip(matrix @ fit.hurdle, -30.0, 30.0))
    )
    shifted_all = shifted_probabilities(raw_all, rows, settlement_s)
    selected = np.flatnonzero(evaluation_mask)
    raw = raw_all[selected]
    shifted = shifted_all[selected]
    labels = (rows["arrival"][selected] == 0).astype(np.float64)
    arrival = rows["arrival"][selected].astype(np.float64)
    expected_parts = []
    crps_parts = []
    sum_error = 0.0
    p0_error = 0.0
    minimum = 1.0
    maximum = 0.0
    for start in range(0, len(selected), chunk_size):
        one = selected[start : start + chunk_size]
        _raw, expected, pmf = score_batch(
            matrix[one],
            rows["current"][one],
            rows["capacity"][one],
            fit.hurdle,
            fit.anchor,
            fit.sign,
            fit.bins,
            fit.fitted,
            full_probability=shifted_all[one],
        )
        expected_parts.append(expected)
        truth = (
            np.arange(71, dtype=np.int16)[None, :]
            >= rows["arrival"][one].astype(np.int16)[:, None]
        ).astype(np.float64)
        crps_parts.append(np.sum((np.cumsum(pmf, axis=1) - truth) ** 2, axis=1))
        sum_error = max(sum_error, float(np.max(np.abs(np.sum(pmf, axis=1) - 1.0))))
        p0_error = max(p0_error, float(np.max(np.abs(pmf[:, 0] - shifted_all[one]))))
        minimum = min(minimum, float(np.min(pmf)))
        maximum = max(maximum, float(np.max(pmf)))
    expected = np.concatenate(expected_parts) if expected_parts else np.asarray([], dtype=np.float64)
    crps = np.concatenate(crps_parts) if crps_parts else np.asarray([], dtype=np.float64)
    metrics = metric_summary(
        raw,
        shifted,
        labels,
        expected,
        arrival,
        crps,
        pmf_sum_error=sum_error,
        pmf_p0_error=p0_error,
        pmf_minimum=minimum,
        pmf_maximum=maximum,
    )
    arrays = {
        "raw": raw,
        "shifted": shifted,
        "labels": labels,
        "expected": expected,
        "arrival": arrival,
        "crps": crps,
        "time_slot": rows["time_slot"][selected].astype(np.int8),
        "date_index": rows["date_index"][selected].astype(np.int8),
    }
    return metrics, arrays


def combined_metric(parts: Sequence[Mapping[str, np.ndarray]], pmf_summaries: Sequence[Mapping[str, Any]]) -> dict[str, Any]:
    if not parts:
        raise ValueError("no_metric_parts")
    arrays = {
        key: np.concatenate([np.asarray(part[key]) for part in parts])
        for key in ("raw", "shifted", "labels", "expected", "arrival", "crps")
    }
    return metric_summary(
        arrays["raw"],
        arrays["shifted"],
        arrays["labels"],
        arrays["expected"],
        arrays["arrival"],
        arrays["crps"],
        pmf_sum_error=max(float(item["maximumSumError"]) for item in pmf_summaries),
        pmf_p0_error=max(float(item["maximumP0Error"]) for item in pmf_summaries),
        pmf_minimum=min(float(item["minimumProbability"]) for item in pmf_summaries),
        pmf_maximum=max(float(item["maximumProbability"]) for item in pmf_summaries),
    )


def compact_metric(arrays: Mapping[str, np.ndarray], selected: np.ndarray) -> dict[str, Any]:
    raw = arrays["raw"][selected]
    shifted = arrays["shifted"][selected]
    labels = arrays["labels"][selected]
    expected = arrays["expected"][selected]
    arrival = arrays["arrival"][selected]
    crps = arrays["crps"][selected]
    eps = 1e-12

    def probability_metric(value: np.ndarray) -> dict[str, float]:
        return {
            "brier": float(np.mean((value - labels) ** 2)),
            "logLoss": float(
                -np.mean(
                    labels * np.log(np.clip(value, eps, 1.0))
                    + (1.0 - labels) * np.log(np.clip(1.0 - value, eps, 1.0))
                )
            ),
            "calibrationInTheLarge": float(np.mean(value) - np.mean(labels)),
        }

    return {
        "rows": int(len(selected)),
        "fullRows": int(np.sum(labels)),
        "fullPrevalence": float(np.mean(labels)),
        "raw": probability_metric(raw),
        "shifted": probability_metric(shifted),
        "expectedSeats": {
            "mae": float(np.mean(np.abs(expected - arrival))),
            "rmse": float(np.sqrt(np.mean((expected - arrival) ** 2))),
            "crps": float(np.mean(crps)),
        },
    }


def stratified_metrics(
    parts: Sequence[Mapping[str, np.ndarray]], dates: Sequence[str]
) -> dict[str, Any]:
    arrays = {
        key: np.concatenate([np.asarray(part[key]) for part in parts])
        for key in (
            "raw", "shifted", "labels", "expected", "arrival", "crps",
            "time_slot", "date_index",
        )
    }
    time_slot = arrays["time_slot"]
    date_index = arrays["date_index"]
    all_rows = np.arange(len(time_slot))
    by_slot = {
        name: compact_metric(arrays, np.flatnonzero(time_slot == index))
        for index, name in enumerate(("MORNING", "EVENING", "OTHER"))
        if np.any(time_slot == index)
    }
    weekend_axis = np.asarray(
        [date.fromisoformat(dates[int(index)]).weekday() >= 5 for index in date_index],
        dtype=bool,
    )
    by_day_type = {
        "WEEKDAY": compact_metric(arrays, all_rows[~weekend_axis]),
        "WEEKEND": compact_metric(arrays, all_rows[weekend_axis]),
    }
    by_date = {
        dates[index]: compact_metric(arrays, np.flatnonzero(date_index == index))
        for index in sorted(set(int(value) for value in date_index))
    }
    return {
        "byTimeSlot": by_slot,
        "byDayType": by_day_type,
        "byDate": by_date,
        "uncertaintyPolicy": "report complete KST-date metric distribution; no row bootstrap",
    }


def full_only_metric(raw: np.ndarray, shifted: np.ndarray, labels: np.ndarray) -> dict[str, Any]:
    labels = np.asarray(labels, dtype=np.float64)
    raw = np.asarray(raw, dtype=np.float64)
    shifted = np.asarray(shifted, dtype=np.float64)
    eps = 1e-12

    def one(probability: np.ndarray) -> dict[str, float]:
        return {
            "brier": float(np.mean((probability - labels) ** 2)),
            "logLoss": float(
                -np.mean(
                    labels * np.log(np.clip(probability, eps, 1.0))
                    + (1.0 - labels) * np.log(np.clip(1.0 - probability, eps, 1.0))
                )
            ),
            "calibrationInTheLarge": float(np.mean(probability) - np.mean(labels)),
        }

    return {
        "rows": int(len(labels)),
        "fullRows": int(np.sum(labels)),
        "raw": one(raw),
        "shifted": one(shifted),
    }


def train_models(
    work: Path,
    dates: Sequence[str],
    guard: int,
    settlement_s: int,
    primary: ScenarioProfiles,
    boundaries: np.ndarray,
    split: Mapping[str, Any],
) -> tuple[dict[str, np.ndarray], dict[str, Any], list[dict[str, Any]], dict[tuple[str, int], BlockFit]]:
    bit = 1 << G_GRID.index(guard)
    development_end = int(split["developmentEndIndex"])
    calibration_end = int(split["calibrationEndIndex"])
    calibration_start_us = date_start_us(dates[development_end])
    holdout_start_us = date_start_us(dates[calibration_end])
    bootstrap = bootstrap_index(
        work, dates, guard, settlement_s, development_end
    )
    if bootstrap is None:
        raise RuntimeError("development_never_reaches_bootstrap_gate")

    tensors = {
        "hurdle_coefficients": np.zeros((2, 12, 31), dtype=np.float64),
        "anchor_coefficients": np.zeros((2, 12, 2), dtype=np.float64),
        "sign_coefficients": np.zeros((2, 12, 2, 31), dtype=np.float64),
        "bin_coefficients": np.zeros((2, 12, 2, 9, 31), dtype=np.float64),
        "bin_fitted": np.zeros((2, 12, 2, 9), dtype=np.uint8),
    }
    final_fits: dict[tuple[str, int], BlockFit] = {}
    block_receipts: dict[str, Any] = {}
    calibration_parts: list[dict[str, np.ndarray]] = []
    holdout_parts: list[dict[str, np.ndarray]] = []
    calibration_pmf: list[dict[str, Any]] = []
    holdout_pmf: list[dict[str, Any]] = []
    oof_parts: list[dict[str, np.ndarray]] = []
    contexts: list[dict[str, Any]] = []
    data_through = 0
    total_refits = 0

    for route_index, route in enumerate(ROUTES):
        for horizon in HORIZONS:
            mapped = block_memmap(work / f"base-{route}-h{horizon}.bin")
            selected = np.asarray(mapped[(mapped["eligible_mask"] & bit) != 0]).copy()
            del mapped
            matrix = features_for(selected, route, horizon, primary, boundaries)
            availability = selected["arrival_us"] + settlement_s * 1_000_000
            final = fit_block(
                matrix,
                selected["current"],
                selected["arrival"],
                selected["capacity"],
            )
            total_refits += 1
            final_fits[(route, horizon)] = final
            h = horizon - 1
            tensors["hurdle_coefficients"][route_index, h] = final.hurdle
            tensors["anchor_coefficients"][route_index, h] = final.anchor
            tensors["sign_coefficients"][route_index, h] = final.sign
            tensors["bin_coefficients"][route_index, h] = final.bins
            tensors["bin_fitted"][route_index, h] = final.fitted
            data_through = max(data_through, int(np.max(selected["arrival_us"])))

            development_train = (
                (selected["date_index"] < development_end)
                & (availability <= calibration_start_us)
            )
            calibration_eval = (
                (selected["date_index"] >= development_end)
                & (selected["date_index"] < calibration_end)
            )
            pre_holdout_train = (
                (selected["date_index"] < calibration_end)
                & (availability <= holdout_start_us)
            )
            holdout_eval = selected["date_index"] >= calibration_end
            development_fit = fit_block(
                matrix[development_train],
                selected["current"][development_train],
                selected["arrival"][development_train],
                selected["capacity"][development_train],
            )
            pre_holdout_fit = fit_block(
                matrix[pre_holdout_train],
                selected["current"][pre_holdout_train],
                selected["arrival"][pre_holdout_train],
                selected["capacity"][pre_holdout_train],
            )
            total_refits += 2
            calibration_metrics, calibration_arrays = evaluate_fit(
                development_fit,
                matrix,
                selected,
                calibration_eval,
                settlement_s,
            )
            holdout_metrics, holdout_arrays = evaluate_fit(
                pre_holdout_fit,
                matrix,
                selected,
                holdout_eval,
                settlement_s,
            )
            calibration_parts.append(calibration_arrays)
            holdout_parts.append(holdout_arrays)
            calibration_pmf.append(calibration_metrics["pmf"])
            holdout_pmf.append(holdout_metrics["pmf"])

            one_oof_raw = []
            one_oof_shifted = []
            one_oof_labels = []
            oof_days = []
            for day_index in range(bootstrap + 1, development_end):
                cutoff_us = date_start_us(dates[day_index])
                training = (
                    (selected["date_index"] < day_index)
                    & (availability <= cutoff_us)
                )
                evaluating = selected["date_index"] == day_index
                if not np.any(evaluating):
                    continue
                hurdle, _anchor = fit_hurdle_anchor(
                    matrix[training],
                    selected["current"][training],
                    selected["arrival"][training],
                    selected["capacity"][training],
                )
                total_refits += 1
                raw_all = 1.0 / (
                    1.0 + np.exp(-np.clip(matrix @ hurdle, -30.0, 30.0))
                )
                shifted_all = shifted_probabilities(raw_all, selected, settlement_s)
                one_oof_raw.append(raw_all[evaluating])
                one_oof_shifted.append(shifted_all[evaluating])
                one_oof_labels.append((selected["arrival"][evaluating] == 0).astype(np.float64))
                oof_days.append(dates[day_index])
            if one_oof_raw:
                oof_parts.append(
                    {
                        "raw": np.concatenate(one_oof_raw),
                        "shifted": np.concatenate(one_oof_shifted),
                        "labels": np.concatenate(one_oof_labels),
                    }
                )

            key = f"{route}:{horizon}"
            block_receipts[key] = {
                "finalFit": dict(final.counts),
                "completedDates": int(len(np.unique(selected["date_index"]))),
                "developmentTrainingRows": int(np.sum(development_train)),
                "calibrationRows": int(np.sum(calibration_eval)),
                "preHoldoutTrainingRows": int(np.sum(pre_holdout_train)),
                "holdoutRows": int(np.sum(holdout_eval)),
                "oofDates": oof_days,
                "calibration": calibration_metrics,
                "holdout": holdout_metrics,
            }
            if route in ROUTES and horizon in (1, 6, 12):
                choices = np.flatnonzero(holdout_eval)
                if len(choices):
                    choice = int(choices[0])
                    context = {
                        "modelRoute": route,
                        "stopsAhead": horizon,
                        "currentSeats": int(selected["current"][choice]),
                        "capacity": int(selected["capacity"][choice]),
                        "actualArrivalSeats": int(selected["arrival"][choice]),
                        "featureVector": [float(value) for value in matrix[choice]],
                        "sourceClass": "deidentified_actual_holdout_context",
                    }
                    raw_value, expected_value, pmf = score_one(
                        matrix[choice],
                        context["currentSeats"],
                        context["capacity"],
                        final.hurdle,
                        final.anchor,
                        final.sign,
                        final.bins,
                        final.fitted,
                    )
                    context["finalRefitRawFullChance"] = raw_value
                    context["finalRefitExpectedSeats"] = expected_value
                    context["pmfSum"] = float(np.sum(pmf))
                    contexts.append(context)
            print(
                json.dumps(
                    {
                        "block": key,
                        "rows": len(selected),
                        "status": "fit",
                    },
                    sort_keys=True,
                ),
                flush=True,
            )
            del selected, matrix

    calibration_combined = combined_metric(calibration_parts, calibration_pmf)
    holdout_combined = combined_metric(holdout_parts, holdout_pmf)
    calibration_combined["stratified"] = stratified_metrics(
        calibration_parts, dates
    )
    holdout_combined["stratified"] = stratified_metrics(holdout_parts, dates)
    oof_raw = np.concatenate([part["raw"] for part in oof_parts])
    oof_shifted = np.concatenate([part["shifted"] for part in oof_parts])
    oof_labels = np.concatenate([part["labels"] for part in oof_parts])
    receipt = {
        "schemaVersion": "v4-1-training-and-evaluation-v1",
        "fitProfile": {
            "ridge": 1.0,
            "irlsMaximumIterations": 60,
            "irlsWeightFloor": 1e-9,
            "irlsTolerance": 1e-10,
            "anchorMaximumIterations": 40,
            "anchorTolerance": 1e-9,
            "directionMinimumRows": 50,
            "binPositiveMinimumRows": 10,
            "blockMinimumRows": 200,
            "blockMinimumDates": 2,
            "linearAlgebraDtype": "float64",
        },
        "selectedGSeconds": guard,
        "selectedSSeconds": settlement_s,
        "bootstrapCutoffDate": dates[bootstrap],
        "chronologicalRefitCount": total_refits,
        "postprocessingOutsideJava": False,
        "fixedPointCellIterationCount": 0,
        "split": dict(split),
        "blocks": block_receipts,
        "combined": {
            "developmentOof": full_only_metric(oof_raw, oof_shifted, oof_labels),
            "calibration": calibration_combined,
            "holdout": holdout_combined,
        },
        "finalRefit": {
            "usesAllCompleteDates": True,
            "dataThrough": utc_text_from_us(data_through),
            "holdoutMetricBelongsToPreFinalRefitCandidate": True,
        },
    }
    return tensors, receipt, contexts, final_fits


def sensitivity_fits(
    work: Path,
    scenarios: Sequence[ScenarioProfiles],
    primary: ScenarioProfiles,
    final_fits: Mapping[tuple[str, int], BlockFit],
    gates: Mapping[int, Mapping[str, Any]],
    boundaries: np.ndarray,
) -> dict[str, Any]:
    results = []
    primary_bit = 1 << G_GRID.index(primary.guard_g)
    for scenario in scenarios:
        gate = gates[scenario.guard_g]
        if not gate["passesExistingBlockGate"]:
            results.append(
                {
                    "scenario": scenario.name,
                    "gSeconds": scenario.guard_g,
                    "sSeconds": scenario.settlement_s,
                    "status": "UNFITTABLE_EXISTING_BLOCK_GATE",
                    "failedBlocks": gate["failedBlocks"],
                }
            )
            continue
        bit = 1 << G_GRID.index(scenario.guard_g)
        max_hurdle = 0.0
        max_anchor = 0.0
        max_cell = 0.0
        squared_hurdle = 0.0
        rows_total = 0
        common_total = 0
        for route in ROUTES:
            for horizon in HORIZONS:
                mapped = block_memmap(work / f"base-{route}-h{horizon}.bin")
                scenario_rows = np.asarray(mapped[(mapped["eligible_mask"] & bit) != 0]).copy()
                matrix = features_for(scenario_rows, route, horizon, scenario, boundaries)
                hurdle, anchor = fit_hurdle_anchor(
                    matrix,
                    scenario_rows["current"],
                    scenario_rows["arrival"],
                    scenario_rows["capacity"],
                )
                reference = final_fits[(route, horizon)]
                difference = hurdle - reference.hurdle
                max_hurdle = max(max_hurdle, float(np.max(np.abs(difference))))
                squared_hurdle += float(np.sum(difference**2))
                max_anchor = max(max_anchor, float(np.max(np.abs(anchor - reference.anchor))))
                rows_total += len(scenario_rows)

                common = np.asarray(mapped[(mapped["eligible_mask"] & primary_bit) != 0]).copy()
                primary_matrix = features_for(common, route, horizon, primary, boundaries)
                scenario_common = features_for(common, route, horizon, scenario, boundaries)
                max_cell = max(
                    max_cell,
                    float(np.max(np.abs(primary_matrix[:, 28:31] - scenario_common[:, 28:31]))),
                )
                common_total += len(common)
                del mapped, scenario_rows, matrix, common, primary_matrix, scenario_common
        results.append(
            {
                "scenario": scenario.name,
                "gSeconds": scenario.guard_g,
                "sSeconds": scenario.settlement_s,
                "status": "FIT",
                "rows": rows_total,
                "commonPrimaryRowsCompared": common_total,
                "maximumAbsoluteCellFeatureDifference": max_cell,
                "maximumAbsoluteHurdleCoefficientDifference": max_hurdle,
                "hurdleCoefficientL2Difference": math.sqrt(squared_hurdle),
                "maximumAbsoluteAnchorCoefficientDifference": max_anchor,
                "coefficientSensitivityScope": "full-data hurdle and anchor",
            }
        )
        print(
            json.dumps({"scenario": scenario.name, "status": "sensitivity-fit"}, sort_keys=True),
            flush=True,
        )
    return {
        "schemaVersion": "v4-1-guard-sensitivity-v1",
        "selectionRule": (
            "largest non-stress G whose every route-horizon block retains at least "
            "200 rows, two complete KST dates, and one positive-seat arrival; S=60 "
            "is fixed by the deployed settlement interval and specification primary"
        ),
        "primaryScenario": primary.name,
        "results": results,
    }


def write_seed(
    output: Path,
    primary: ScenarioProfiles,
    materials: Mapping[str, RouteReplayMaterial],
    boundaries: np.ndarray,
    source_cutoff_us: int | Mapping[str, int],
    route_reference_digest: str,
) -> dict[str, Any]:
    final_boundary = int(boundaries[-1])
    cutoff_by_route = (
        {route: int(source_cutoff_us) for route in ROUTES}
        if isinstance(source_cutoff_us, int)
        else {route: int(source_cutoff_us[route]) for route in ROUTES}
    )
    guard_index = G_GRID.index(primary.guard_g)
    rows_out: list[dict[str, Any]] = []
    route_counts: dict[str, Any] = {}
    for route in ROUTES:
        material = materials[route]
        route_cutoff = cutoff_by_route[route]
        caps_at_cutoff = capacity_at_boundaries(
            np.asarray([route_cutoff], dtype=np.int64),
            material.vehicle_count,
            material.capacity_changes,
        )
        eligible = (material.h1_rows["eligible_mask"] & (1 << guard_index)) != 0
        active = eligible & (
            material.h1_rows["arrival_us"] + primary.settlement_s * 1_000_000
            <= route_cutoff
        )
        selected = material.h1_rows[active]
        capacities = caps_at_cutoff[-1, selected["vehicle"]].astype(np.float64)
        valid = capacities > 0
        selected = selected[valid]
        capacities = capacities[valid]
        grouped: dict[tuple[int, int], list[float]] = {}
        for index, row in enumerate(selected):
            key = (int(row["arrival_hour_utc"]), int(row["target"]))
            totals = grouped.setdefault(key, [0.0, 0.0, 0.0, 0.0])
            capacity = float(capacities[index])
            totals[0] += 1.0 - float(row["arrival"]) / capacity
            totals[1] += float(row["current"]) - float(row["arrival"])
            totals[2] += capacity
            totals[3] += 1.0
        for (hour_utc, target), totals in sorted(grouped.items()):
            hour_start = datetime.fromtimestamp(hour_utc * 3600, tz=UTC)
            rows_out.append(
                {
                    "modelRoute": route,
                    "stopOrder": target,
                    "arrivalDateKst": hour_start.astimezone(KST).date().isoformat(),
                    "arrivalHourStartUtc": hour_start.isoformat().replace("+00:00", "Z"),
                    "fillRateTotal": totals[0],
                    "netBoardingTotal": totals[1],
                    "capacityTotal": totals[2],
                    "sampleCount": int(totals[3]),
                }
            )
        route_counts[route] = {
            "hourlyRows": len(grouped),
            "samples": int(len(selected)),
            "finalRevision": int(primary.revisions[route][-1]),
            "replay": material.replay_counts,
        }
    document = {
        "schemaVersion": "stop-demand-hourly-aggregate-seed-v1",
        "classification": "PRIVACY_SAFE_AGGREGATE_BACKFILL_SEED_DRY_RUN_ONLY",
        "scope": "SOURCE_SIDE_THROUGH_TARGET_AUTHORITY",
        "featureContractVersion": FEATURE_CONTRACT_VERSION,
        "backfillPolicyId": BACKFILL_POLICY_TEMPLATE.format(
            g=primary.guard_g, s=primary.settlement_s
        ),
        "routeReference": {
            "version": ROUTE_REFERENCE_VERSION,
            "digest": route_reference_digest,
        },
        "sourceAuthorityThroughExclusiveUtcByRoute": {
            route: utc_text_from_us(cutoff_by_route[route]) for route in ROUTES
        },
        "lastBackfilledGenerationUtc": utc_text_from_us(final_boundary),
        "generatedAtGuardSeconds": primary.guard_g,
        "settlementAvailabilityGuardSeconds": primary.settlement_s,
        "requiresRdsObservationDeltaBeforeFormalCutover": True,
        "rdsObservationDeltaContract": "rds-observation-delta-contract.json",
        "rows": rows_out,
        "privacy": {
            "containsVehicleIdentifiers": False,
            "containsVehicleHmacs": False,
            "containsPlateValues": False,
            "containsRawRows": False,
        },
    }
    uncompressed = canonical_json(document)
    compressed = gzip.compress(uncompressed, compresslevel=9, mtime=0)
    seed_path = output / "seed" / "cell-hourly-aggregate.json.gz"
    seed_path.parent.mkdir(parents=True, exist_ok=True)
    temporary = seed_path.with_name(f".{seed_path.name}.tmp")
    temporary.write_bytes(compressed)
    os.replace(temporary, seed_path)
    receipt = {
        "schemaVersion": "stop-demand-hourly-aggregate-seed-receipt-v1",
        "path": "seed/cell-hourly-aggregate.json.gz",
        "compressedBytes": len(compressed),
        "uncompressedBytes": len(uncompressed),
        "compressedSha256": sha256_bytes(compressed),
        "canonicalJsonSha256": sha256_bytes(uncompressed),
        "rowCount": len(rows_out),
        "routeCounts": route_counts,
        "sourceAuthorityThroughExclusiveUtcByRoute": {
            route: utc_text_from_us(cutoff_by_route[route]) for route in ROUTES
        },
        "lastBackfilledGenerationUtc": utc_text_from_us(final_boundary),
        "databaseWritePerformed": False,
        "requiresRdsObservationDeltaBeforeFormalCutover": True,
    }
    safe_write_json(output / "seed" / "receipt.json", receipt)
    return receipt


def export_provisional_bundle(
    output: Path,
    tensors: Mapping[str, np.ndarray],
    contexts: Sequence[Mapping[str, Any]],
    training_receipt: Mapping[str, Any],
    source_manifest_sha256: str,
    route_reference_digest: str,
    guard: int,
    settlement_s: int,
) -> dict[str, Any]:
    bundle = output / "bundle"
    if bundle.exists() or bundle.is_symlink():
        raise RuntimeError("bundle_output_already_exists")
    bundle.mkdir(parents=True)
    weights_path = bundle / "weights.safetensors"
    contiguous = {
        name: np.ascontiguousarray(tensors[name])
        for name in TENSOR_SPECS
    }
    save_file(contiguous, str(weights_path))
    weights_digest = file_sha256(weights_path)
    policy_id = BACKFILL_POLICY_TEMPLATE.format(g=guard, s=settlement_s)
    release_material = "\n".join(
        (weights_digest, source_manifest_sha256, FEATURE_CONTRACT_VERSION, policy_id)
    ).encode("utf-8")
    release_id = f"v41b-{sha256_bytes(release_material)[:20]}"
    golden_features = [1.0] + [0.0] * 30
    golden = {
        "featureVector": golden_features,
        "modelRoute": "3330",
        "stopsAhead": 6,
        "currentSeats": 12,
        "capacity": 44,
        "expectedFullChance": 0.0,
        "expectedSeats": 0.0,
    }
    manifest = {
        "bundleSchemaVersion": "a18-live-bundle-v1",
        "modelVersion": "seat-distribution-a18-v1",
        "releaseId": release_id,
        "featureContractVersion": FEATURE_CONTRACT_VERSION,
        # No artifact commit is allowed by this assignment. The field therefore
        # pins the exact authoritative consumer/feature implementation; the
        # uncommitted builder is separately bound by SHA-256 in the receipt.
        "sourceCommit": TARGET_DEV_COMMIT,
        "routeReference": {
            "version": ROUTE_REFERENCE_VERSION,
            "digest": route_reference_digest,
        },
        "routes": list(ROUTES),
        "horizonStops": list(HORIZONS),
        "featureNames": list(FEATURE_NAMES),
        "normalizationConstants": {
            "largestSeatCount": 68.0,
            "lowSeatBand": 20.0,
        },
        "timeSlotSource": "observation_batch.response_received_at",
        "capacityPolicy": "maximum-seats-ever-observed",
        "cellStatisticsPolicy": (
            f"{FEATURE_CONTRACT_VERSION};backfill={policy_id};aggregate-seed-required"
        ),
        "tensors": tensor_declarations(),
        "weightsDigest": weights_digest,
        "identityDigest": "0" * 64,
        "goldenVectorDigest": "0" * 64,
        "goldenVector": golden,
        "dataThrough": training_receipt["finalRefit"]["dataThrough"],
    }
    if len(manifest) != 19:
        raise RuntimeError("manifest_top_level_field_count_not_19")
    safe_write_json(bundle / "manifest.json", manifest, canonical=True)
    return {
        "releaseId": release_id,
        "weightsSha256": weights_digest,
        "manifestProvisionalSha256": file_sha256(bundle / "manifest.json"),
        "goldenStatus": "REQUIRES_EXACT_JAVA_PROBE",
        "bundleDirectory": "bundle",
    }


def main() -> int:
    args = arguments()
    started = time.monotonic()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    expected_audit = json.loads(args.source_audit.read_text(encoding="utf-8"))
    expected_watermark = json.loads(
        args.label_watermark_audit.read_text(encoding="utf-8")
    )
    expected_catchup = json.loads(args.catchup_audit.read_text(encoding="utf-8"))
    expected_route_catchup = (
        None
        if args.route_catchup_audit is None
        else json.loads(args.route_catchup_audit.read_text(encoding="utf-8"))
    )
    protocol = load_protocol(args.protocol)
    rosters, route_reference_digest = route_rosters(protocol)
    dates = audit_source.calendar_days(args.from_date, args.through_date)
    work = Path(tempfile.mkdtemp(prefix=".build-derived-", dir=output))
    try:
        compact, vehicle_counts, source_receipt = load_source(
            args,
            protocol,
            expected_audit,
            expected_watermark,
            expected_catchup,
            expected_route_catchup,
        )
        source_receipt["routeReference"] = {
            "version": ROUTE_REFERENCE_VERSION,
            "digest": route_reference_digest,
            "effectiveFrom": "2026-01-01",
            "effectiveThrough": None,
            "routes": {
                route: {
                    "largestStopOrder": int(rosters[route]["largest"]),
                    "boardingStops": len(rosters[route]["boarding"]),
                    "nonBoardingStops": int(rosters[route]["largest"])
                    - len(rosters[route]["boarding"]),
                    "turnStationSeq": int(rosters[route]["turnStationSeq"]),
                }
                for route in ROUTES
            },
            "targetRdsValidityUsedToDiscardSource": False,
        }
        safe_write_json(output / "processed" / "source-load-receipt.json", source_receipt)
        checkpoint(
            args.checkpoint_script,
            "v4-1 S3 base+catch-up authority closure 재검증 완료; row 원문·식별자 비저장",
        )

        lag_rows: dict[tuple[str, int, int, int], dict[str, Any]] = {}
        materials: dict[str, RouteReplayMaterial] = {}
        if expected_route_catchup is None:
            one_cutoff = to_us(
                datetime.fromisoformat(
                    str(expected_catchup["targetAuthorityFromUtcInclusive"]).replace(
                        "Z", "+00:00"
                    )
                )
            )
            now_by_route = {route: one_cutoff for route in ROUTES}
        else:
            now_by_route = {
                route: to_us(
                    datetime.fromisoformat(
                        str(expected_route_catchup["routes"][route]["boundaryLowerUtc"]).replace(
                            "Z", "+00:00"
                        )
                    )
                )
                for route in ROUTES
            }
        now_us = max(now_by_route.values())
        for route in ROUTES:
            materials[route] = materialize_route(
                route,
                compact[route],
                vehicle_counts[route],
                rosters[route],
                dates,
                work,
                lag_rows,
                now_by_route[route],
            )
            del compact[route]
        compact.clear()
        detailed_lag, aggregate_lag = lag_receipt_rows(lag_rows, dates)
        selected_g, guard_gates = select_guard(work, dates)
        split = split_dates(dates)
        lag_receipt = {
            "schemaVersion": "v4-1-generated-at-lag-sensitivity-v1",
            "gridSeconds": list(G_GRID[:6]),
            "stressGridSeconds": list(G_GRID[6:]),
            "continuousInvariantPoints": "{0,G} union candidate timestamp crossings",
            "selectionRule": (
                "largest non-stress G passing every existing 24-block minimum gate"
            ),
            "selectedGSeconds": selected_g,
            "aggregate": {str(key): value for key, value in aggregate_lag.items()},
            "blockDate": detailed_lag,
            "blockGates": {str(key): value for key, value in guard_gates.items()},
        }
        safe_write_json(output / "processed" / "lag-sensitivity.json", lag_receipt)
        checkpoint(
            args.checkpoint_script,
            f"v4-1 lag invariant materialization 완료; 선택 G={selected_g}s, S grid 준비",
        )

        boundaries = generation_boundaries(dates[0], now_us)
        scenario_specs = [(guard, PRIMARY_S) for guard in G_GRID]
        scenario_specs.extend(
            (selected_g, settlement)
            for settlement in S_GRID
            if settlement != PRIMARY_S
        )
        profiles = []
        for guard, settlement in scenario_specs:
            name = f"g{guard}-s{settlement}"
            profiles.append(
                build_profiles(
                    name,
                    guard,
                    settlement,
                    materials,
                    rosters,
                    boundaries,
                )
            )
            print(json.dumps({"scenario": name, "status": "cell-backfill"}), flush=True)
        primary = next(
            item
            for item in profiles
            if item.guard_g == selected_g and item.settlement_s == PRIMARY_S
        )
        seed_receipt = write_seed(
            output,
            primary,
            materials,
            boundaries,
            now_by_route,
            route_reference_digest,
        )
        cell_receipt = {
            "schemaVersion": "v4-1-chronological-cell-backfill-v1",
            "generationScheduleKst": ["00:00", "06:00", "12:00", "18:00"],
            "phaseOrder": [
                "observations",
                "settlements_available",
                "generation",
                "as_of_join",
                "structural_forecast",
            ],
            "fixedPointIterationCount": 0,
            "profiles": {
                item.name: {
                    "gSeconds": item.guard_g,
                    "sSeconds": item.settlement_s,
                    "routes": item.summary,
                }
                for item in profiles
            },
            "seed": seed_receipt,
        }
        safe_write_json(output / "processed" / "cell-backfill-receipt.json", cell_receipt)
        checkpoint(
            args.checkpoint_script,
            "v4-1 chronological cell backfill 및 aggregate seed 생성 완료; DB write 없음",
        )

        tensors, training_receipt, contexts, final_fits = train_models(
            work,
            dates,
            selected_g,
            PRIMARY_S,
            primary,
            boundaries,
            split,
        )
        safe_write_json(output / "processed" / "training-receipt.json", training_receipt)
        safe_write_json(output / "processed" / "runtime-contexts.json", {
            "schemaVersion": "v4-1-deidentified-runtime-contexts-v1",
            "contexts": contexts,
        })
        checkpoint(
            args.checkpoint_script,
            "v4-1 chronological train/calibration/holdout/final refit 완료",
        )

        sensitivity = sensitivity_fits(
            work,
            profiles,
            primary,
            final_fits,
            guard_gates,
            boundaries,
        )
        safe_write_json(output / "processed" / "fit-sensitivity.json", sensitivity)
        catchup_identity = json.dumps(
            source_receipt["catchUpAuthority"].get("routes", {}),
            sort_keys=True,
            separators=(",", ":"),
        )
        if not source_receipt["catchUpAuthority"].get("routes"):
            catchup_identity = "\n".join(
                (
                    source_receipt["catchUpAuthority"]["acceptedManifestSha256"],
                    source_receipt["catchUpAuthority"][
                        "targetAuthorityFromUtcInclusive"
                    ],
                )
            )
        combined_source_digest = sha256_bytes(
            "\n".join(
                (
                    source_receipt["acceptedManifestSha256"],
                    catchup_identity,
                )
            ).encode("utf-8")
        )
        bundle_receipt = export_provisional_bundle(
            output,
            tensors,
            contexts,
            training_receipt,
            combined_source_digest,
            route_reference_digest,
            selected_g,
            PRIMARY_S,
        )
        script_digest = file_sha256(Path(__file__).resolve())
        training_release = sha256_bytes(
            "\n".join(
                (
                    combined_source_digest,
                    script_digest,
                    BACKFILL_POLICY_TEMPLATE.format(g=selected_g, s=PRIMARY_S),
                )
            ).encode("utf-8")
        )
        build_receipt = {
            "schemaVersion": "v4-1-backfilled-bundle-build-receipt-v1",
            "classification": (
                f"V4_1_LOADER_COMPATIBLE_BACKFILLED_PROVISIONAL_{len(dates)}D"
                if split["releaseQualification"] != "PASS"
                else "V4_1_COMPATIBLE_BACKFILLED"
            ),
            "targetDevCommit": TARGET_DEV_COMMIT,
            "identifiers": {
                "modelReleaseId": bundle_receipt["releaseId"],
                "trainingReleaseId": training_release,
                "replayPolicyId": BACKFILL_POLICY_TEMPLATE.format(
                    g=selected_g, s=PRIMARY_S
                ),
                "dataReleaseId": f"s3-{combined_source_digest[:20]}",
                "featureContractVersion": FEATURE_CONTRACT_VERSION,
            },
            "bundle": bundle_receipt,
            "source": {
                "fromDate": dates[0],
                "throughDate": dates[-1],
                "completeDates": len(dates),
                "acceptedManifestSha256": source_receipt["acceptedManifestSha256"],
                "catchUpAcceptedManifestSha256": source_receipt["catchUpAuthority"][
                    "acceptedManifestSha256"
                ],
                "combinedSourceClosureSha256": combined_source_digest,
                "targetAuthorityFromUtcInclusive": source_receipt[
                    "catchUpAuthority"
                ]["targetAuthorityFromUtcInclusive"],
                "routeCatchUpAuthority": source_receipt["catchUpAuthority"].get(
                    "routes", {}
                ),
                "coefficientPredictionDatesExcludeCatchUpPartialDate": True,
                "seedReplayIncludesCatchUpPartialDate": True,
            },
            "training": {
                "selectedGSeconds": selected_g,
                "selectedSSeconds": PRIMARY_S,
                "releaseQualification": split["releaseQualification"],
                "dataThrough": training_receipt["finalRefit"]["dataThrough"],
            },
            "implementation": {
                "builderSha256": script_digest,
                "numericalContractSha256": file_sha256(
                    Path(__file__).with_name("v41_model.py")
                ),
                "sourceCommitFieldUsesAuthoritativeConsumerCommit": True,
                "artifactCommitUnavailableBecauseCommitsAreForbidden": True,
            },
            "privacy": {
                "rawRowsPersisted": False,
                "originalVehicleIdsPersisted": False,
                "vehicleHmacsPersisted": False,
                "plateValuesPersisted": False,
                "secretsPersisted": False,
            },
            "externalWrites": {
                "awsWrites": False,
                "databaseWrites": False,
                "deployment": False,
            },
            "runtime": {
                "durationSeconds": round(time.monotonic() - started, 3),
                "peakRssMiB": peak_rss_mib(),
            },
            "nextRequiredStep": "exact Java probe, manifest finalization, strict verify",
        }
        safe_write_json(output / "processed" / "build-receipt.json", build_receipt)
        print(
            json.dumps(
                {
                    "releaseId": bundle_receipt["releaseId"],
                    "selectedGSeconds": selected_g,
                    "selectedSSeconds": PRIMARY_S,
                    "status": "provisional-bundle-built",
                },
                sort_keys=True,
            )
        )
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (KeyboardInterrupt, SystemExit):
        raise
    except Exception as error:
        # Provider exceptions can include private paths or object keys. Only a
        # fixed exception class is emitted here; local debugging uses aggregate
        # stage receipts rather than raw exception text.
        detail = str(error)
        safe_detail = detail if re.fullmatch(r"[A-Za-z0-9_.:-]{1,160}", detail) else None
        print(
            json.dumps(
                {
                    "status": "failed",
                    "errorClass": type(error).__name__,
                    **({} if safe_detail is None else {"safeCode": safe_detail}),
                },
                sort_keys=True,
            ),
            file=sys.stderr,
        )
        raise SystemExit(1)
