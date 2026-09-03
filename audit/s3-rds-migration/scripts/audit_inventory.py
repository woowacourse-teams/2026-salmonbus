#!/usr/bin/env python3
"""Aggregate-only audit of private collector records.

The process is deliberately allowed to hold source rows and identifiers only in
memory.  It never writes an object body, object key, vehicle identifier, plate
value, HMAC value, or row-level export.  The output contains counts, schemas,
collision rates, byte estimates, and content-addressed inventory digests only.
"""

from __future__ import annotations

import argparse
import collections
import concurrent.futures
import dataclasses
import hashlib
import hmac
import json
import math
import os
import re
import shutil
import subprocess
import threading
import zlib
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence
from zoneinfo import ZoneInfo

import boto3


UTC = timezone.utc
KST = ZoneInfo("Asia/Seoul")
FREEZE_TIME = time(0, 15)
SOURCE_ACCOUNT = "827325854159"
COLLECTOR_SCHEMA = "1.0.0"
BUS_FIELDS = (
    "vehId",
    "plateNo",
    "routeId",
    "routeTypeCd",
    "stationId",
    "stationSeq",
    "remainSeatCnt",
    "crowded",
    "lowPlate",
    "taglessCd",
    "stateCd",
)
SENSITIVE_BUS_FIELDS = frozenset({"vehId", "plateNo"})
STRICT_HMAC = re.compile(r"^hmac-sha256:[0-9a-f]{64}$")
RECORD_PATH = re.compile(
    r"^records/route=(?P<route>1650|3330)/"
    r"dt=(?P<date>\d{4}-\d{2}-\d{2})/hh=(?P<hour>\d{2})/"
    r"(?P<stamp>\d{8}T\d{6}\.\d{3}Z)_"
    r"(?P<attempt>[A-Za-z0-9-]{8})_(?P<suffix>1650|3330)\.record\.json$"
)
RAW_PATH = re.compile(
    r"^raw/route=(?P<route>1650|3330)/"
    r"dt=(?P<date>\d{4}-\d{2}-\d{2})/hh=(?P<hour>\d{2})/"
    r"(?P<stamp>\d{8}T\d{6}\.\d{3}Z)_"
    r"(?P<attempt>[A-Za-z0-9-]{8})_(?P<suffix>1650|3330)\.json$"
)
PARTITION = re.compile(
    r"^(?P<family>[^/]+)/route=(?P<route>[^/]+)/dt=(?P<date>\d{4}-\d{2}-\d{2})/"
)
SEMANTIC_BATCH_DOMAIN = b"salmonbus-s3-batch-v1\0"
INVENTORY_ALGORITHM = {
    "version": "object-inventory-v1",
    "ordering": "global_bytewise_key_ascending",
    "line_format": "key<TAB>etag<TAB>size<LF>",
    "encoding": "UTF-8",
    "final_lf": True,
}


@dataclasses.dataclass(frozen=True, repr=False)
class ObjectInfo:
    key: str
    etag: str
    size: int
    last_modified: datetime


@dataclasses.dataclass(repr=False)
class RecordResult:
    day: str | None
    route: str | None
    size: int
    schema_version: str | None
    record_shape: str | None
    response_shape: str | None
    classification: str | None
    strategy_version: str | None
    provider_rows: int
    storable_rows: int
    excluded_rows: int
    seat_known_rows: int
    seat_reported_unknown_rows: int
    seat_not_reported_rows: int
    crowd_preserved_rows: int
    crowd_folded_to_null_rows: int
    station_mismatch_rows: int
    station_unknown_rows: int
    duplicate_vehicle_rows: int
    raw_expected: bool
    raw_reference: str | None
    raw_reference_matches_path: bool
    embedded_row_bijection: bool
    error_codes: tuple[str, ...]
    identity_values: dict[str, bytes]
    vehicle_pairs: tuple[tuple[bytes, bytes], ...]
    field_stats: dict[str, dict[str, Any]]
    archive_line: bytes | None


class ZstdCounter:
    """Count zstd output bytes while discarding them immediately."""

    def __init__(self, binary: str | None) -> None:
        self.enabled = binary is not None
        self.count = 0
        self._process: subprocess.Popen[bytes] | None = None
        self._thread: threading.Thread | None = None
        self._failure = False
        if binary is None:
            return
        self._process = subprocess.Popen(
            [binary, "--quiet", "--stdout", "-3"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        self._thread = threading.Thread(target=self._drain, daemon=True)
        self._thread.start()

    def _drain(self) -> None:
        assert self._process is not None and self._process.stdout is not None
        try:
            while True:
                chunk = self._process.stdout.read(1024 * 1024)
                if not chunk:
                    break
                self.count += len(chunk)
        except Exception:
            self._failure = True

    def write(self, payload: bytes) -> None:
        if not self.enabled:
            return
        assert self._process is not None and self._process.stdin is not None
        try:
            self._process.stdin.write(payload)
        except Exception:
            self._failure = True
            self.enabled = False

    def finish(self) -> int | None:
        if self._process is None:
            return None
        assert self._process.stdin is not None
        try:
            self._process.stdin.close()
        except Exception:
            self._failure = True
        if self._thread is not None:
            self._thread.join()
        return_code = self._process.wait()
        if return_code != 0 or self._failure:
            return None
        return self.count


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--profile", default="default")
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--through-date", required=True)
    parser.add_argument("--route-reference-protocol", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def iso_utc(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_datetime(value: Any) -> datetime | None:
    if not isinstance(value, str) or not value:
        return None
    rendered = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(rendered)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(UTC)


def parse_time_pair(value: Any) -> datetime | None:
    if not isinstance(value, Mapping):
        return None
    return parse_datetime(value.get("utc"))


def scalar_text(value: Any) -> str | None:
    if value is None or isinstance(value, (bool, Mapping, list)):
        return None
    return str(value)


def integer(value: Any) -> int | None:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    if not math.isfinite(float(value)) or not float(value).is_integer():
        return None
    return int(value)


def type_name(value: Any) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, int):
        return "integer"
    if isinstance(value, float):
        return "number"
    if isinstance(value, str):
        return "string"
    if isinstance(value, list):
        return "array"
    if isinstance(value, Mapping):
        return "object"
    return "other"


def shape_of(value: Any, depth: int = 0) -> Any:
    if depth > 8:
        return type_name(value)
    if isinstance(value, Mapping):
        return {
            str(key): shape_of(child, depth + 1)
            for key, child in sorted(value.items(), key=lambda item: str(item[0]))
        }
    if isinstance(value, list):
        distinct: dict[str, Any] = {}
        for child in value:
            shape = shape_of(child, depth + 1)
            encoded = json.dumps(shape, sort_keys=True, separators=(",", ":"))
            distinct[encoded] = shape
        return {"type": "array", "item_shapes": [distinct[key] for key in sorted(distinct)]}
    return type_name(value)


def encoded_shape(value: Any) -> str:
    return json.dumps(shape_of(value), ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest_text(domain: bytes, *values: Any) -> bytes:
    digest = hashlib.sha256(domain)
    for value in values:
        payload = "<null>" if value is None else str(value)
        encoded = payload.encode("utf-8")
        digest.update(len(encoded).to_bytes(4, "big"))
        digest.update(encoded)
    return digest.digest()


def inventory_digest(objects: Iterable[ObjectInfo]) -> str:
    digest = hashlib.sha256()
    for item in sorted(objects, key=lambda candidate: candidate.key.encode("utf-8")):
        digest.update(f"{item.key}\t{item.etag}\t{item.size}\n".encode("utf-8"))
    return digest.hexdigest()


def expected_raw_key(match: re.Match[str]) -> str:
    return (
        f"raw/route={match.group('route')}/dt={match.group('date')}/"
        f"hh={match.group('hour')}/{match.group('stamp')}_"
        f"{match.group('attempt')}_{match.group('suffix')}.json"
    )


def list_objects(client: Any, bucket: str) -> list[ObjectInfo]:
    result: list[ObjectInfo] = []
    paginator = client.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, PaginationConfig={"PageSize": 1000}):
        for item in page.get("Contents", ()):
            key = item.get("Key")
            modified = item.get("LastModified")
            size = item.get("Size")
            etag = item.get("ETag")
            if (
                not isinstance(key, str)
                or key.endswith("/")
                or not isinstance(modified, datetime)
                or isinstance(size, bool)
                or not isinstance(size, int)
                or not isinstance(etag, str)
            ):
                continue
            result.append(
                ObjectInfo(
                    key=key,
                    etag=etag.strip('"'),
                    size=size,
                    last_modified=modified.astimezone(UTC),
                )
            )
    return sorted(result, key=lambda item: item.key.encode("utf-8"))


def family_of(key: str) -> str:
    return key.split("/", 1)[0] if "/" in key else "root"


def freeze_at(day: str) -> datetime:
    following = date.fromisoformat(day) + timedelta(days=1)
    return datetime.combine(following, FREEZE_TIME, tzinfo=KST).astimezone(UTC)


def quantiles(values: Sequence[float]) -> dict[str, float | None]:
    if not values:
        return {"min": None, "p50": None, "p95": None, "p99": None, "max": None}
    ordered = sorted(values)

    def at(fraction: float) -> float:
        index = min(len(ordered) - 1, max(0, math.ceil(fraction * len(ordered)) - 1))
        return round(float(ordered[index]), 3)

    return {
        "min": round(float(ordered[0]), 3),
        "p50": at(0.50),
        "p95": at(0.95),
        "p99": at(0.99),
        "max": round(float(ordered[-1]), 3),
    }


def load_route_reference(path: Path) -> tuple[dict[str, Any], str]:
    body = path.read_bytes()
    protocol = json.loads(body)
    section = protocol.get("route_reference", {})
    versions = []
    for version in section.get("versions", []):
        routes = {}
        for route, block in version.get("routes", {}).items():
            routes[str(route)] = {
                int(sequence): str(stop_id)
                for sequence, stop_id in block.get("stations", {}).items()
            }
        versions.append(
            {
                "id": version.get("route_reference_version_id"),
                "from": version.get("effective_from"),
                "through": version.get("effective_through"),
                "routes": routes,
            }
        )
    reference = {
        "protocol_version": protocol.get("protocol_version"),
        "station_id_mismatch_policy": section.get("station_id_mismatch_policy"),
        "unknown_station_seq_policy": section.get("unknown_station_seq_policy"),
        "versions": versions,
    }
    return reference, hashlib.sha256(body).hexdigest()


def route_roster(reference: Mapping[str, Any], day: str, route: str) -> Mapping[int, str] | None:
    for version in reference.get("versions", []):
        first = version.get("from")
        last = version.get("through")
        if isinstance(first, str) and day >= first and (last is None or day <= last):
            routes = version.get("routes", {})
            roster = routes.get(route)
            return roster if isinstance(roster, Mapping) else None
    return None


def raw_rows(document: Any) -> list[Mapping[str, Any]]:
    if not isinstance(document, Mapping):
        return []
    response = document.get("response")
    if not isinstance(response, Mapping):
        return []
    body = response.get("msgBody")
    if not isinstance(body, Mapping):
        return []
    rows = body.get("busLocationList")
    if rows is None:
        return []
    if isinstance(rows, Mapping):
        return [rows]
    if isinstance(rows, list):
        return [row for row in rows if isinstance(row, Mapping)]
    return []


def field_observation(row: Mapping[str, Any], field: str) -> dict[str, Any]:
    if field not in row:
        return {"missing": 1}
    value = row.get(field)
    result: dict[str, Any] = {"present": 1, "types": {type_name(value): 1}}
    if value is None:
        result["null"] = 1
    else:
        result["non_null"] = 1
    if field not in SENSITIVE_BUS_FIELDS:
        numeric = integer(value)
        if numeric is not None:
            result["numeric_min"] = numeric
            result["numeric_max"] = numeric
    return result


def merge_field_stats(target: dict[str, Any], incoming: Mapping[str, Any]) -> None:
    for field, stats in incoming.items():
        output = target.setdefault(
            field,
            {
                "present": 0,
                "missing": 0,
                "null": 0,
                "non_null": 0,
                "types": {},
                "numeric_min": None,
                "numeric_max": None,
            },
        )
        for name in ("present", "missing", "null", "non_null"):
            output[name] += int(stats.get(name, 0))
        for name, count in stats.get("types", {}).items():
            output["types"][name] = output["types"].get(name, 0) + int(count)
        if "numeric_min" in stats:
            value = stats["numeric_min"]
            output["numeric_min"] = value if output["numeric_min"] is None else min(output["numeric_min"], value)
        if "numeric_max" in stats:
            value = stats["numeric_max"]
            output["numeric_max"] = value if output["numeric_max"] is None else max(output["numeric_max"], value)


def get_record_body(client: Any, bucket: str, info: ObjectInfo) -> bytes | None:
    try:
        response = client.get_object(Bucket=bucket, Key=info.key)
        stream = response.get("Body")
        if stream is None:
            return None
        try:
            body = stream.read()
        finally:
            close = getattr(stream, "close", None)
            if callable(close):
                close()
        if not isinstance(body, bytes):
            return None
        if len(body) != info.size or response.get("ContentLength", len(body)) != info.size:
            return None
        response_etag = response.get("ETag")
        if not isinstance(response_etag, str) or response_etag.strip('"') != info.etag:
            return None
        return body
    except Exception:
        return None


def map_outcome(classification: Any, result_code: Any) -> tuple[str, str | None]:
    kind = classification if isinstance(classification, str) else ""
    if kind == "SUCCESS_WITH_VEHICLES":
        return "SUCCESS_ROWS", None
    if kind in {"SUCCESS_ZERO_VEHICLES", "SUCCESS_NO_VEHICLE"}:
        return "SUCCESS_EMPTY", None
    if kind == "TRANSPORT_ERROR":
        return "UNKNOWN_AFTER_DISPATCH", None
    if kind in {"PARSE_ERROR", "INCOMPLETE_ENVELOPE"}:
        return "FAILED_UNREADABLE", None
    if kind in {"HTTP_ERROR", "API_ERROR"}:
        code = integer(result_code)
        if code == 1 or code == 2:
            return "FAILED_UPSTREAM", "UPSTREAM_ERROR"
        return "FAILED_UPSTREAM", "UPSTREAM_ERROR"
    return "FAILED_UNREADABLE", None


def audit_record(
    client: Any,
    bucket: str,
    info: ObjectInfo,
    route_reference: Mapping[str, Any],
    ephemeral_key: bytes,
) -> RecordResult:
    match = RECORD_PATH.fullmatch(info.key)
    day = match.group("date") if match else None
    route_from_path = match.group("route") if match else None
    errors: list[str] = []
    if match is None:
        errors.append("invalid_record_partition")
    body = get_record_body(client, bucket, info)
    if body is None:
        return RecordResult(
            day=day,
            route=route_from_path,
            size=info.size,
            schema_version=None,
            record_shape=None,
            response_shape=None,
            classification=None,
            strategy_version=None,
            provider_rows=0,
            storable_rows=0,
            excluded_rows=0,
            seat_known_rows=0,
            seat_reported_unknown_rows=0,
            seat_not_reported_rows=0,
            crowd_preserved_rows=0,
            crowd_folded_to_null_rows=0,
            station_mismatch_rows=0,
            station_unknown_rows=0,
            duplicate_vehicle_rows=0,
            raw_expected=False,
            raw_reference=None,
            raw_reference_matches_path=False,
            embedded_row_bijection=False,
            error_codes=tuple((*errors, "record_read_or_snapshot_mismatch")),
            identity_values={},
            vehicle_pairs=(),
            field_stats={},
            archive_line=None,
        )
    try:
        document = json.loads(body.decode("utf-8-sig"))
    except Exception:
        document = None
    if not isinstance(document, Mapping):
        errors.append("invalid_record_json_or_root")
        return RecordResult(
            day=day,
            route=route_from_path,
            size=info.size,
            schema_version=None,
            record_shape=None,
            response_shape=None,
            classification=None,
            strategy_version=None,
            provider_rows=0,
            storable_rows=0,
            excluded_rows=0,
            seat_known_rows=0,
            seat_reported_unknown_rows=0,
            seat_not_reported_rows=0,
            crowd_preserved_rows=0,
            crowd_folded_to_null_rows=0,
            station_mismatch_rows=0,
            station_unknown_rows=0,
            duplicate_vehicle_rows=0,
            raw_expected=False,
            raw_reference=None,
            raw_reference_matches_path=False,
            embedded_row_bijection=False,
            error_codes=tuple(errors),
            identity_values={},
            vehicle_pairs=(),
            field_stats={},
            archive_line=None,
        )

    schema_version = document.get("schema_version") if isinstance(document.get("schema_version"), str) else None
    if schema_version != COLLECTOR_SCHEMA:
        errors.append("unsupported_collector_schema")
    route_node = document.get("route") if isinstance(document.get("route"), Mapping) else {}
    route = scalar_text(route_node.get("name"))
    route_id = scalar_text(route_node.get("route_id"))
    if match and (route != route_from_path or route != match.group("suffix")):
        errors.append("route_partition_mismatch")

    timing = document.get("timing") if isinstance(document.get("timing"), Mapping) else {}
    started_at = parse_time_pair(timing.get("request_started_at"))
    received_at = parse_time_pair(timing.get("response_received_at"))
    if started_at is None:
        errors.append("invalid_request_started_at")
    if received_at is None or (started_at is not None and received_at < started_at):
        errors.append("invalid_response_received_at")
    if started_at is not None and match:
        local = started_at.astimezone(KST)
        if local.date().isoformat() != day or f"{local.hour:02d}" != match.group("hour"):
            errors.append("request_partition_mismatch")

    buses = document.get("buses") if isinstance(document.get("buses"), list) else []
    classification_node = (
        document.get("classification") if isinstance(document.get("classification"), Mapping) else {}
    )
    classification = (
        classification_node.get("type") if isinstance(classification_node.get("type"), str) else None
    )
    declared_vehicle_count = integer(classification_node.get("vehicle_count"))
    if declared_vehicle_count != len(buses):
        errors.append("record_row_count_mismatch")
    collection = document.get("collection") if isinstance(document.get("collection"), Mapping) else {}
    strategy = collection.get("strategy_version") if isinstance(collection.get("strategy_version"), str) else None
    scheduled_at = parse_datetime(collection.get("scheduled_at"))
    round_index = integer(collection.get("round_index"))
    invocation_id = document.get("invocation_id") if isinstance(document.get("invocation_id"), str) else None

    http = document.get("http") if isinstance(document.get("http"), Mapping) else {}
    response_sha = http.get("response_sha256") if isinstance(http.get("response_sha256"), str) else None
    raw_node = document.get("raw_response") if isinstance(document.get("raw_response"), Mapping) else {}
    raw_reference = raw_node.get("s3_key") if isinstance(raw_node.get("s3_key"), str) else None
    expected = expected_raw_key(match) if match else None
    raw_expected = raw_reference is not None
    raw_reference_matches = raw_reference == expected if raw_expected and expected else not raw_expected
    if not raw_reference_matches:
        errors.append("raw_reference_path_mismatch")

    response_envelope = document.get("response_envelope")
    embedded_rows = raw_rows(response_envelope)
    row_bijection = len(buses) == len(embedded_rows)
    if row_bijection:
        for normalized, raw in zip(buses, embedded_rows):
            if not isinstance(normalized, Mapping) or any(normalized.get(field) != raw.get(field) for field in BUS_FIELDS):
                row_bijection = False
                break
    if not row_bijection:
        errors.append("embedded_response_row_mismatch")

    field_stats: dict[str, dict[str, Any]] = {}
    storable_rows: list[dict[str, Any]] = []
    seat_known = 0
    seat_reported_unknown = 0
    seat_not_reported = 0
    crowd_preserved = 0
    crowd_folded = 0
    station_mismatch = 0
    station_unknown = 0
    duplicate_vehicle_rows = 0
    vehicle_seen: set[bytes] = set()
    vehicle_pairs: list[tuple[bytes, bytes]] = []
    roster = route_roster(route_reference, day, route) if day and route else None

    for row_number, candidate in enumerate(buses):
        if not isinstance(candidate, Mapping):
            errors.append("invalid_record_row")
            continue
        merge_field_stats(field_stats, {field: field_observation(candidate, field) for field in BUS_FIELDS})
        vehicle_value = scalar_text(candidate.get("vehId"))
        plate_value = scalar_text(candidate.get("plateNo"))
        pseudonyms = candidate.get("pseudonyms") if isinstance(candidate.get("pseudonyms"), Mapping) else {}
        vehicle_hmac = pseudonyms.get("vehId_hmac") if isinstance(pseudonyms.get("vehId_hmac"), str) else None
        plate_hmac = pseudonyms.get("plateNo_hmac") if isinstance(pseudonyms.get("plateNo_hmac"), str) else None
        if vehicle_value is not None and (vehicle_hmac is None or STRICT_HMAC.fullmatch(vehicle_hmac) is None):
            errors.append("invalid_vehicle_hmac")
        if vehicle_value is None:
            errors.append("missing_vehicle_id_for_private_archive")
        if plate_value is not None and (plate_hmac is None or STRICT_HMAC.fullmatch(plate_hmac) is None):
            errors.append("invalid_plate_hmac")
        vehicle_token = (
            hmac.new(ephemeral_key, ("vehicle\0" + vehicle_value).encode("utf-8"), hashlib.sha256).digest()
            if vehicle_value is not None
            else None
        )
        hmac_token = (
            hmac.new(ephemeral_key, ("hmac\0" + vehicle_hmac).encode("utf-8"), hashlib.sha256).digest()
            if vehicle_hmac is not None
            else None
        )
        if vehicle_token is not None:
            if vehicle_token in vehicle_seen:
                duplicate_vehicle_rows += 1
            vehicle_seen.add(vehicle_token)
        if vehicle_token is not None and hmac_token is not None:
            vehicle_pairs.append((vehicle_token, hmac_token))

        stop_order = integer(candidate.get("stationSeq"))
        stop_id = scalar_text(candidate.get("stationId"))
        running_state = integer(candidate.get("stateCd"))
        passed_stop_order = (
            stop_order - 1 if stop_order is not None and running_state == 1 else stop_order
        )
        storable = (
            stop_order is not None
            and stop_id is not None
            and running_state in {0, 1, 2}
            and passed_stop_order is not None
            and passed_stop_order >= 0
        )
        if roster is None or stop_order not in roster:
            station_unknown += 1
        elif stop_id != roster[stop_order]:
            station_mismatch += 1

        seats = integer(candidate.get("remainSeatCnt"))
        if candidate.get("remainSeatCnt") is None:
            remaining_seats = None
            seat_reason = "NOT_REPORTED"
            seat_not_reported += 1
        elif seats is None or seats < 0:
            remaining_seats = None
            seat_reason = "REPORTED_UNKNOWN"
            seat_reported_unknown += 1
        else:
            remaining_seats = seats
            seat_reason = None
            seat_known += 1
        crowd = integer(candidate.get("crowded"))
        crowd_level = crowd if crowd in {1, 2, 3, 4} else None
        if crowd_level is None:
            crowd_folded += 1
        else:
            crowd_preserved += 1

        if storable:
            storable_rows.append(
                {
                    "source_row_number": row_number,
                    "vehicle_id": vehicle_value,
                    "stop_order": stop_order,
                    "stop_id": stop_id,
                    "passed_stop_order": passed_stop_order,
                    "running_state": running_state,
                    "remaining_seats": remaining_seats,
                    "seat_unknown_reason": seat_reason,
                    "crowd_level": crowd_level,
                    "vehicle_type": integer(candidate.get("lowPlate")),
                    "route_type": integer(candidate.get("routeTypeCd")),
                    "tagless": integer(candidate.get("taglessCd")),
                }
            )

    record_id = document.get("record_id") if isinstance(document.get("record_id"), str) else None
    if not record_id:
        errors.append("missing_record_id")
    started_text = iso_utc(started_at) if started_at is not None else None
    received_text = iso_utc(received_at) if received_at is not None else None
    scheduled_text = iso_utc(scheduled_at) if scheduled_at is not None else None
    semantic_digest_bytes = digest_text(
        SEMANTIC_BATCH_DOMAIN,
        SOURCE_ACCOUNT,
        route_id,
        started_text,
        received_text,
        response_sha,
        round_index,
    )
    semantic_digest = semantic_digest_bytes.hex()
    identity_values: dict[str, bytes] = {}
    candidates = {
        "source_record_id": record_id,
        "route_request_started_at_millisecond": (route, started_text) if route and started_text else None,
        "route_request_started_at_second": (
            route,
            started_at.replace(microsecond=0).isoformat() if route and started_at else None,
        ) if route and started_at else None,
        "route_response_received_at_millisecond": (route, received_text) if route and received_text else None,
        "route_scheduled_at_round": (route, scheduled_text, round_index) if route and scheduled_text and round_index else None,
        "invocation_route_round": (invocation_id, route, round_index) if invocation_id and route and round_index else None,
        "semantic_batch_digest": semantic_digest,
        "response_body_sha256": response_sha if raw_expected else None,
        "record_body_sha256": hashlib.sha256(body).hexdigest(),
    }
    for name, value in candidates.items():
        if value is not None:
            identity_values[name] = digest_text(b"audit-candidate-v1\0", value)

    outcome, failure_code = map_outcome(classification, document.get("api", {}).get("resultCode") if isinstance(document.get("api"), Mapping) else None)
    archive_line = None
    if not errors and station_mismatch == 0:
        batch = {
            "source_account": SOURCE_ACCOUNT,
            "source_schema_version": schema_version,
            "source_record_id": record_id,
            "semantic_batch_digest": semantic_digest,
            "attempt_key": "s3v1:" + semantic_digest,
            "route_name": route,
            "source_route_id": route_id,
            "scheduled_at": scheduled_text or started_text,
            "requested_at": started_text,
            "response_received_at": received_text,
            "attempt_number": 1,
            "http_status": integer(http.get("status")),
            "result_code": integer(document.get("api", {}).get("resultCode") if isinstance(document.get("api"), Mapping) else None),
            "outcome": outcome,
            "failure_code": failure_code,
            "provider_rows": len(buses),
            "stored_rows": len(storable_rows),
            "excluded_rows": len(buses) - len(storable_rows),
            "normalization_version": "normalization-v1.0.0-s3-backfill",
            "collection_strategy_version": strategy,
            "ingestion_origin": "S3_BACKFILL",
        }
        archive_line = (
            json.dumps(
                {"batch": batch, "observations": storable_rows},
                ensure_ascii=False,
                sort_keys=True,
                separators=(",", ":"),
            ).encode("utf-8")
            + b"\n"
        )

    return RecordResult(
        day=day,
        route=route,
        size=info.size,
        schema_version=schema_version,
        record_shape=encoded_shape(document),
        response_shape=encoded_shape(response_envelope) if response_envelope is not None else None,
        classification=classification,
        strategy_version=strategy,
        provider_rows=len(buses),
        storable_rows=len(storable_rows),
        excluded_rows=len(buses) - len(storable_rows),
        seat_known_rows=seat_known,
        seat_reported_unknown_rows=seat_reported_unknown,
        seat_not_reported_rows=seat_not_reported,
        crowd_preserved_rows=crowd_preserved,
        crowd_folded_to_null_rows=crowd_folded,
        station_mismatch_rows=station_mismatch,
        station_unknown_rows=station_unknown,
        duplicate_vehicle_rows=duplicate_vehicle_rows,
        raw_expected=raw_expected,
        raw_reference=raw_reference,
        raw_reference_matches_path=raw_reference_matches,
        embedded_row_bijection=row_bijection,
        error_codes=tuple(sorted(set(errors))),
        identity_values=identity_values,
        vehicle_pairs=tuple(vehicle_pairs),
        field_stats=field_stats,
        archive_line=archive_line,
    )


def collision_summary(counter: collections.Counter[bytes], eligible: int) -> dict[str, Any]:
    groups = sum(1 for count in counter.values() if count > 1)
    extras = sum(count - 1 for count in counter.values() if count > 1)
    return {
        "eligible_records": eligible,
        "unique_values": len(counter),
        "collision_groups": groups,
        "colliding_records_beyond_first": extras,
        "collision_rate": round(extras / eligible, 12) if eligible else None,
    }


def canonical_shape_catalog(counter: collections.Counter[str]) -> list[dict[str, Any]]:
    result = []
    for encoded, count in sorted(counter.items(), key=lambda item: (-item[1], item[0])):
        result.append(
            {
                "schema_fingerprint": hashlib.sha256(encoded.encode("utf-8")).hexdigest(),
                "records": count,
                "shape": json.loads(encoded),
            }
        )
    return result


def add_object_aggregate(target: dict[str, Any], info: ObjectInfo, cutoff_day: str) -> None:
    family = family_of(info.key)
    family_node = target.setdefault("by_family", {}).setdefault(family, {"objects": 0, "bytes": 0})
    family_node["objects"] += 1
    family_node["bytes"] += info.size
    target["objects"] += 1
    target["bytes"] += info.size
    match = PARTITION.match(info.key)
    if match is None:
        target["unpartitioned_objects"] += 1
        return
    day = match.group("date")
    route = match.group("route")
    relation = "base" if day <= cutoff_day else "after_base_cutoff"
    node = (
        target.setdefault("by_partition", {})
        .setdefault(day, {})
        .setdefault(route, {})
        .setdefault(family, {"objects": 0, "bytes": 0, "late_objects": 0})
    )
    node["objects"] += 1
    node["bytes"] += info.size
    node["relation_to_base_cutoff"] = relation
    if info.last_modified > freeze_at(day):
        node["late_objects"] += 1


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output already exists")
    if not 1 <= args.workers <= 32:
        raise SystemExit("workers must be between 1 and 32")
    through = date.fromisoformat(args.through_date).isoformat()
    route_reference, protocol_sha256 = load_route_reference(args.route_reference_protocol)
    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    client = session.client("s3")
    observed_at = datetime.now(UTC)
    all_objects = list_objects(client, args.bucket)
    snapshot = [item for item in all_objects if item.last_modified <= observed_at]
    immutable_snapshot = [item for item in snapshot if family_of(item.key) in {"records", "raw"}]
    selected = []
    records = []
    raw = []
    metadata = {"objects": 0, "bytes": 0, "unpartitioned_objects": 0}
    late_delays: dict[str, list[float]] = collections.defaultdict(list)
    ingestion_lags: dict[str, list[float]] = collections.defaultdict(list)
    invalid_partition_paths = collections.Counter()
    for info in snapshot:
        add_object_aggregate(metadata, info, through)
        family = family_of(info.key)
        path_match = RECORD_PATH.fullmatch(info.key) if family == "records" else RAW_PATH.fullmatch(info.key) if family == "raw" else None
        if family in {"records", "raw"} and path_match is None:
            invalid_partition_paths[family] += 1
        if path_match is not None:
            day = path_match.group("date")
            stamp = datetime.strptime(path_match.group("stamp"), "%Y%m%dT%H%M%S.%fZ").replace(tzinfo=UTC)
            ingestion_lags[family].append((info.last_modified - stamp).total_seconds())
            if info.last_modified > freeze_at(day):
                late_delays[family].append((info.last_modified - freeze_at(day)).total_seconds())
            if day <= through:
                selected.append(info)
                if family == "records":
                    records.append(info)
                else:
                    raw.append(info)

    raw_keys = {item.key for item in raw}
    referenced_raw: collections.Counter[str] = collections.Counter()
    errors = collections.Counter()
    classifications = collections.Counter()
    strategies = collections.Counter()
    schema_versions = collections.Counter()
    record_shapes = collections.Counter()
    response_shapes = collections.Counter()
    identities: dict[str, collections.Counter[bytes]] = collections.defaultdict(collections.Counter)
    identity_eligible = collections.Counter()
    daily: dict[str, dict[str, dict[str, int]]] = {}
    aggregate = collections.Counter()
    all_field_stats: dict[str, Any] = {}
    vehicle_to_hmac: dict[bytes, bytes] = {}
    hmac_to_vehicle: dict[bytes, bytes] = {}
    vehicle_identity_conflicts = 0
    gzip_counter = zlib.compressobj(level=6, wbits=31)
    gzip_bytes = 0
    archive_bytes = 0
    shard_gzip: dict[tuple[str, str], tuple[zlib.compressobj, int, int]] = {}
    zstd_counter = ZstdCounter(shutil.which("zstd"))
    ephemeral_key = os.urandom(32)

    def consume(result: RecordResult) -> None:
        nonlocal gzip_bytes, archive_bytes, vehicle_identity_conflicts
        aggregate["record_documents"] += 1
        aggregate["record_bytes"] += result.size
        aggregate["provider_observations"] += result.provider_rows
        aggregate["rds_storable_observations"] += result.storable_rows
        aggregate["rds_excluded_observations"] += result.excluded_rows
        aggregate["seat_known_rows"] += result.seat_known_rows
        aggregate["seat_reported_unknown_rows"] += result.seat_reported_unknown_rows
        aggregate["seat_not_reported_rows"] += result.seat_not_reported_rows
        aggregate["crowd_preserved_rows"] += result.crowd_preserved_rows
        aggregate["crowd_folded_to_null_rows"] += result.crowd_folded_to_null_rows
        aggregate["station_mismatch_rows"] += result.station_mismatch_rows
        aggregate["station_unknown_rows"] += result.station_unknown_rows
        aggregate["duplicate_vehicle_rows_within_batch"] += result.duplicate_vehicle_rows
        if result.raw_expected:
            aggregate["records_declaring_raw"] += 1
        else:
            aggregate["raw_less_records"] += 1
        if result.raw_reference_matches_path:
            aggregate["raw_reference_path_matches"] += 1
        if result.embedded_row_bijection:
            aggregate["embedded_row_bijection_matches"] += 1
        if result.raw_reference is not None:
            referenced_raw[result.raw_reference] += 1
            if result.raw_reference in raw_keys:
                aggregate["referenced_raw_present"] += 1
            else:
                aggregate["referenced_raw_missing"] += 1
        for code in result.error_codes:
            errors[code] += 1
        if result.classification:
            classifications[result.classification] += 1
        if result.strategy_version:
            strategies[result.strategy_version] += 1
        schema_versions[result.schema_version or "<missing>"] += 1
        if result.record_shape:
            record_shapes[result.record_shape] += 1
        if result.response_shape:
            response_shapes[result.response_shape] += 1
        for name, value in result.identity_values.items():
            identities[name][value] += 1
            identity_eligible[name] += 1
        for vehicle_token, hmac_token in result.vehicle_pairs:
            if vehicle_to_hmac.get(vehicle_token, hmac_token) != hmac_token:
                vehicle_identity_conflicts += 1
            if hmac_to_vehicle.get(hmac_token, vehicle_token) != vehicle_token:
                vehicle_identity_conflicts += 1
            vehicle_to_hmac[vehicle_token] = hmac_token
            hmac_to_vehicle[hmac_token] = vehicle_token
        merge_field_stats(all_field_stats, result.field_stats)
        if result.day and result.route:
            node = daily.setdefault(result.day, {}).setdefault(result.route, collections.Counter())
            node["record_documents"] += 1
            node["provider_observations"] += result.provider_rows
            node["rds_storable_observations"] += result.storable_rows
            node["rds_excluded_observations"] += result.excluded_rows
            node["invalid_records"] += int(bool(result.error_codes))
            node["station_mismatch_rows"] += result.station_mismatch_rows
        if result.archive_line is not None and result.day and result.route:
            aggregate["private_archive_eligible_records"] += 1
            aggregate["private_archive_eligible_observations"] += result.storable_rows
            payload = result.archive_line
            archive_bytes += len(payload)
            gzip_bytes += len(gzip_counter.compress(payload))
            zstd_counter.write(payload)
            shard_key = (result.day, result.route)
            compressor, compressed, uncompressed = shard_gzip.get(
                shard_key, (zlib.compressobj(level=6, wbits=31), 0, 0)
            )
            compressed += len(compressor.compress(payload))
            uncompressed += len(payload)
            shard_gzip[shard_key] = (compressor, compressed, uncompressed)

    records.sort(key=lambda item: item.key.encode("utf-8"))
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        for start in range(0, len(records), args.workers * 8):
            window = records[start : start + args.workers * 8]
            futures = [
                executor.submit(audit_record, client, args.bucket, info, route_reference, ephemeral_key)
                for info in window
            ]
            for future in futures:
                try:
                    consume(future.result())
                except Exception:
                    errors["unexpected_record_audit_failure"] += 1
                    aggregate["record_documents"] += 1
            processed = min(len(records), start + len(window))
            if processed % 10000 < len(window) or processed == len(records):
                print(json.dumps({"processed_records": processed, "status": "auditing"}, sort_keys=True), flush=True)

    gzip_bytes += len(gzip_counter.flush())
    zstd_bytes = zstd_counter.finish()
    route_day_shards = []
    for (day, route), (compressor, compressed, uncompressed) in sorted(shard_gzip.items()):
        compressed += len(compressor.flush())
        route_day_shards.append(
            {
                "date": day,
                "route": route,
                "uncompressed_bytes": uncompressed,
                "gzip_level_6_bytes": compressed,
            }
        )

    duplicate_raw_reference_groups = sum(1 for count in referenced_raw.values() if count > 1)
    duplicate_raw_reference_extras = sum(count - 1 for count in referenced_raw.values() if count > 1)
    orphan_raw = len(raw_keys - set(referenced_raw))
    raw_reference_summary = {
        "record_documents": len(records),
        "raw_documents": len(raw),
        "records_declaring_raw": aggregate["records_declaring_raw"],
        "raw_less_records": aggregate["raw_less_records"],
        "raw_reference_path_matches": aggregate["raw_reference_path_matches"],
        "referenced_raw_present": aggregate["referenced_raw_present"],
        "referenced_raw_missing": aggregate["referenced_raw_missing"],
        "duplicate_raw_reference_groups": duplicate_raw_reference_groups,
        "duplicate_raw_reference_extras": duplicate_raw_reference_extras,
        "unreferenced_raw_documents": orphan_raw,
        "embedded_record_envelope_row_matches": aggregate["embedded_row_bijection_matches"],
        "byte_level_verification": "see source-validation-frozen.json",
    }

    post_observed_at = datetime.now(UTC)
    post_objects = list_objects(client, args.bucket)
    post_snapshot = [item for item in post_objects if item.last_modified <= observed_at]
    post_immutable_snapshot = [
        item for item in post_snapshot if family_of(item.key) in {"records", "raw"}
    ]
    post_new = [item for item in post_objects if item.last_modified > observed_at]
    delta = collections.Counter()
    for item in post_new:
        family = family_of(item.key)
        match = PARTITION.match(item.key)
        if match is None:
            relation = "unpartitioned"
        else:
            relation = "late_for_base" if match.group("date") <= through else "after_base_cutoff"
        delta[(family, relation)] += 1

    identity_output = {
        "object_key": {
            "eligible_records": len(records),
            "unique_values": len(records),
            "collision_groups": 0,
            "colliding_records_beyond_first": 0,
            "collision_rate": 0.0,
            "caveat": "not stored by the current RDS schema and does not detect a copied record under another key",
        }
    }
    for name in sorted(identities):
        identity_output[name] = collision_summary(identities[name], identity_eligible[name])

    route_reference_summary = {
        "protocol_sha256": protocol_sha256,
        "protocol_version": route_reference.get("protocol_version"),
        "station_id_mismatch_policy": route_reference.get("station_id_mismatch_policy"),
        "unknown_station_seq_policy": route_reference.get("unknown_station_seq_policy"),
        "versions": [
            {
                "route_reference_version_id": version.get("id"),
                "effective_from": version.get("from"),
                "effective_through": version.get("through"),
                "route_stop_counts": {
                    route: len(stops) for route, stops in sorted(version.get("routes", {}).items())
                },
            }
            for version in route_reference.get("versions", [])
        ],
    }

    receipt = {
        "schema_version": "salmonbus-s3-rds-audit-inventory-v1",
        "source_account": SOURCE_ACCOUNT,
        "bucket": args.bucket,
        "region": args.region,
        "observed_at_utc": iso_utc(observed_at),
        "audit_completed_at_utc": iso_utc(post_observed_at),
        "base_cutoff_kst_date_inclusive": through,
        "aws_operations": ["s3:ListBucket", "s3:GetObject"],
        "inventory_algorithm": INVENTORY_ALGORITHM,
        "inventory": {
            **metadata,
            "snapshot_sha256": inventory_digest(snapshot),
            "selected_base_sha256": inventory_digest(selected),
            "selected_base_objects": len(selected),
            "selected_base_bytes": sum(item.size for item in selected),
            "invalid_partition_paths": dict(sorted(invalid_partition_paths.items())),
            "late_objects_after_next_day_00_15_kst": {
                family: {"objects": len(values), "delay_seconds": quantiles(values)}
                for family, values in sorted(late_delays.items())
            },
            "object_creation_lag_from_key_timestamp_seconds": {
                family: quantiles(values) for family, values in sorted(ingestion_lags.items())
            },
        },
        "record_aggregate": {
            key: aggregate[key]
            for key in (
                "record_documents",
                "record_bytes",
                "provider_observations",
                "rds_storable_observations",
                "rds_excluded_observations",
                "seat_known_rows",
                "seat_reported_unknown_rows",
                "seat_not_reported_rows",
                "crowd_preserved_rows",
                "crowd_folded_to_null_rows",
                "station_mismatch_rows",
                "station_unknown_rows",
                "duplicate_vehicle_rows_within_batch",
                "private_archive_eligible_records",
                "private_archive_eligible_observations",
            )
        },
        "classifications": dict(sorted(classifications.items())),
        "collection_strategy_versions": dict(sorted(strategies.items())),
        "collector_schema_versions": dict(sorted(schema_versions.items())),
        "record_schemas": canonical_shape_catalog(record_shapes),
        "response_envelope_schemas": canonical_shape_catalog(response_shapes),
        "bus_field_profiles": all_field_stats,
        "daily_route_distribution": {
            day: {route: dict(sorted(values.items())) for route, values in sorted(routes.items())}
            for day, routes in sorted(daily.items())
        },
        "record_errors": dict(sorted(errors.items())),
        "record_raw_bijection": raw_reference_summary,
        "natural_identity_candidates": identity_output,
        "vehicle_identity_consistency": {
            "distinct_private_vehicle_tokens": len(vehicle_to_hmac),
            "distinct_private_hmac_tokens": len(hmac_to_vehicle),
            "bidirectional_mapping_conflicts": vehicle_identity_conflicts,
            "values_emitted": False,
        },
        "route_reference": route_reference_summary,
        "private_sensitive_archive_measurement": {
            "data_classification": "PRIVATE_SENSITIVE_NORMALIZED",
            "contains_vehicle_id": True,
            "contains_plate_hmac_or_raw": False,
            "format": "canonical JSON Lines; one batch envelope with nested observations per line",
            "eligible_record_documents": aggregate["private_archive_eligible_records"],
            "eligible_observation_rows": aggregate["private_archive_eligible_observations"],
            "uncompressed_bytes": archive_bytes,
            "gzip_level_6_bytes": gzip_bytes,
            "zstd_level_3_bytes": zstd_bytes,
            "measurement_sink": "discarded in memory/pipe; no row-level archive persisted",
            "route_day_units": route_day_shards,
        },
        "snapshot_reconciliation": {
            "immutable_data_manifest_sha256_before": inventory_digest(immutable_snapshot),
            "immutable_data_manifest_sha256_after": inventory_digest(post_immutable_snapshot),
            "immutable_data_same_cutoff_stable": (
                inventory_digest(immutable_snapshot) == inventory_digest(post_immutable_snapshot)
            ),
            "whole_bucket_manifest_sha256_before": inventory_digest(snapshot),
            "whole_bucket_manifest_sha256_after": inventory_digest(post_snapshot),
            "whole_bucket_same_cutoff_stable": inventory_digest(snapshot) == inventory_digest(post_snapshot),
            "whole_bucket_caveat": "control counters are mutable and bucket versioning is not enabled; migration stability is asserted only for records/raw",
            "objects_created_after_observed_at": len(post_new),
            "objects_created_after_observed_at_by_family_and_relation": [
                {"family": family, "relation": relation, "objects": count}
                for (family, relation), count in sorted(delta.items())
            ],
        },
        "privacy": {
            "row_level_material_persisted": False,
            "object_keys_emitted": False,
            "original_vehicle_ids_emitted": False,
            "plate_values_emitted": False,
            "source_hmac_values_emitted": False,
            "credentials_or_environment_values_emitted": False,
            "ephemeral_identity_counting_key_persisted": False,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    ephemeral_key = b""
    print(
        json.dumps(
            {
                "record_documents": receipt["record_aggregate"]["record_documents"],
                "rds_storable_observations": receipt["record_aggregate"]["rds_storable_observations"],
                "immutable_data_same_cutoff_stable": receipt["snapshot_reconciliation"]["immutable_data_same_cutoff_stable"],
                "status": "succeeded",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:
        raise SystemExit("audit failed without emitting source identifiers") from None
