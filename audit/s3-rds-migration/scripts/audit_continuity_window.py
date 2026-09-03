#!/usr/bin/env python3
"""Verify one active KST partition and split it at target authority times.

Only aggregate evidence is persisted. Source object keys, rows, identifiers,
plates, HMAC values, response bodies, and credentials remain in memory.
"""

from __future__ import annotations

import argparse
import collections
import concurrent.futures
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping

import boto3

import audit_inventory as base


UTC = timezone.utc


def iso_exact(value: datetime) -> str:
    return value.astimezone(UTC).isoformat(timespec="microseconds").replace("+00:00", "Z")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--profile", default="default")
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--partition-date", required=True)
    parser.add_argument(
        "--target-authority-from",
        help="one UTC authority boundary shared by every route (legacy mode)",
    )
    parser.add_argument(
        "--route-authority",
        action="append",
        default=[],
        metavar="ROUTE=UTC_TIMESTAMP",
        help="route-specific UTC authority boundary; specify once per route",
    )
    parser.add_argument("--route-reference-protocol", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def list_prefix(client: Any, bucket: str, prefix: str, cutoff: datetime) -> list[base.ObjectInfo]:
    result = []
    paginator = client.get_paginator("list_objects_v2")
    for page in paginator.paginate(Bucket=bucket, Prefix=prefix, PaginationConfig={"PageSize": 1000}):
        for item in page.get("Contents", ()):
            key = item.get("Key")
            modified = item.get("LastModified")
            size = item.get("Size")
            etag = item.get("ETag")
            if (
                not isinstance(key, str)
                or key.endswith("/")
                or not isinstance(modified, datetime)
                or modified.astimezone(UTC) > cutoff
                or isinstance(size, bool)
                or not isinstance(size, int)
                or not isinstance(etag, str)
            ):
                continue
            result.append(
                base.ObjectInfo(
                    key=key,
                    etag=etag.strip('"'),
                    size=size,
                    last_modified=modified.astimezone(UTC),
                )
            )
    return result


def list_partition(client: Any, bucket: str, day: str, cutoff: datetime) -> tuple[list[base.ObjectInfo], list[base.ObjectInfo]]:
    records: list[base.ObjectInfo] = []
    raw: list[base.ObjectInfo] = []
    for route in ("1650", "3330"):
        records.extend(list_prefix(client, bucket, f"records/route={route}/dt={day}/", cutoff))
        raw.extend(list_prefix(client, bucket, f"raw/route={route}/dt={day}/", cutoff))
    records.sort(key=lambda item: item.key.encode("utf-8"))
    raw.sort(key=lambda item: item.key.encode("utf-8"))
    return records, raw


def get_body(client: Any, bucket: str, info: base.ObjectInfo) -> tuple[bytes | None, Mapping[str, str]]:
    try:
        response = client.get_object(Bucket=bucket, Key=info.key)
        stream = response.get("Body")
        if stream is None:
            return None, {}
        try:
            body = stream.read()
        finally:
            close = getattr(stream, "close", None)
            if callable(close):
                close()
        response_etag = response.get("ETag")
        if (
            not isinstance(body, bytes)
            or len(body) != info.size
            or response.get("ContentLength", len(body)) != info.size
            or not isinstance(response_etag, str)
            or response_etag.strip('"') != info.etag
        ):
            return None, {}
        metadata = response.get("Metadata")
        return body, metadata if isinstance(metadata, Mapping) else {}
    except Exception:
        return None, {}


def segment_template() -> dict[str, Any]:
    return {
        "record_documents": 0,
        "accepted_record_documents": 0,
        "accepted_raw_documents": 0,
        "accepted_raw_less_records": 0,
        "raw_documents_referenced": 0,
        "raw_less_records": 0,
        "provider_observations": 0,
        "rds_storable_observations": 0,
        "accepted_rds_storable_observations": 0,
        "quarantined_records": 0,
        "quarantined_observations": 0,
        "record_bytes": 0,
        "raw_bytes_referenced": 0,
        "first_response_received_at_utc": None,
        "last_response_received_at_utc": None,
        "by_route": {},
        "by_classification": {},
        "by_schema": {},
        "by_http_status": {},
        "by_api_result_code": {},
        "by_mapped_outcome": {},
        "by_mapped_failure_code": {},
        "classification_time_ranges": {},
    }


def parse_authorities(args: argparse.Namespace) -> dict[str, datetime]:
    authorities: dict[str, datetime] = {}
    for item in args.route_authority:
        route, separator, value = item.partition("=")
        parsed = base.parse_datetime(value) if separator else None
        if route not in {"1650", "3330"} or parsed is None or route in authorities:
            raise SystemExit("invalid or duplicate --route-authority")
        authorities[route] = parsed
    if authorities:
        if args.target_authority_from is not None or set(authorities) != {"1650", "3330"}:
            raise SystemExit("route authority mode requires exactly 1650 and 3330 and no shared boundary")
        return authorities
    shared = base.parse_datetime(args.target_authority_from)
    if shared is None:
        raise SystemExit("a shared or per-route target authority time is required")
    return {route: shared for route in ("1650", "3330")}


def update_time_range(segment: dict[str, Any], observed: datetime) -> None:
    value = base.iso_utc(observed)
    first = segment["first_response_received_at_utc"]
    last = segment["last_response_received_at_utc"]
    if first is None or value < first:
        segment["first_response_received_at_utc"] = value
    if last is None or value > last:
        segment["last_response_received_at_utc"] = value


def audit_one(
    client: Any,
    bucket: str,
    info: base.ObjectInfo,
    raw_by_key: Mapping[str, base.ObjectInfo],
    reference: Mapping[str, Any],
    authorities: Mapping[str, datetime],
) -> dict[str, Any]:
    result: dict[str, Any] = {
        "segment": "unknown",
        "route": None,
        "schema": None,
        "classification": None,
        "http_status": None,
        "api_result_code": None,
        "mapped_outcome": None,
        "mapped_failure_code": None,
        "observed_at": None,
        "provider_observations": 0,
        "rds_storable_observations": 0,
        "record_bytes": info.size,
        "raw_bytes": 0,
        "raw_reference": None,
        "raw_expected": False,
        "errors": [],
        "quarantined": False,
        "identity": None,
    }
    match = base.RECORD_PATH.fullmatch(info.key)
    if match is None:
        result["errors"].append("invalid_record_partition")
        return result
    result["route"] = match.group("route")
    body, _metadata = get_body(client, bucket, info)
    if body is None:
        result["errors"].append("record_read_or_snapshot_mismatch")
        return result
    try:
        document = json.loads(body.decode("utf-8-sig"))
    except Exception:
        result["errors"].append("invalid_record_json")
        return result
    if not isinstance(document, Mapping):
        result["errors"].append("invalid_record_root")
        return result

    schema = document.get("schema_version")
    result["schema"] = schema if isinstance(schema, str) else "<missing>"
    if schema != base.COLLECTOR_SCHEMA:
        result["errors"].append("unsupported_collector_schema")
    route_node = document.get("route") if isinstance(document.get("route"), Mapping) else {}
    route = base.scalar_text(route_node.get("name"))
    if route != match.group("route") or route != match.group("suffix"):
        result["errors"].append("route_partition_mismatch")
    result["route"] = route
    timing = document.get("timing") if isinstance(document.get("timing"), Mapping) else {}
    observed = base.parse_time_pair(timing.get("response_received_at"))
    started = base.parse_time_pair(timing.get("request_started_at"))
    if observed is None or started is None or observed < started:
        result["errors"].append("invalid_timing")
        return result
    result["observed_at"] = observed
    authority = authorities.get(str(route))
    if authority is None:
        result["errors"].append("missing_route_authority")
        return result
    result["segment"] = "source_catch_up" if observed < authority else "target_authority_overlap"
    classification_node = document.get("classification") if isinstance(document.get("classification"), Mapping) else {}
    classification = classification_node.get("type")
    result["classification"] = classification if isinstance(classification, str) else "<missing>"
    http = document.get("http") if isinstance(document.get("http"), Mapping) else {}
    api = document.get("api") if isinstance(document.get("api"), Mapping) else {}
    result["http_status"] = base.integer(http.get("status"))
    result["api_result_code"] = base.integer(api.get("resultCode"))
    outcome, failure_code = base.map_outcome(classification, result["api_result_code"])
    result["mapped_outcome"] = outcome
    result["mapped_failure_code"] = failure_code
    buses = document.get("buses") if isinstance(document.get("buses"), list) else []
    result["provider_observations"] = len(buses)
    if base.integer(classification_node.get("vehicle_count")) != len(buses):
        result["errors"].append("record_row_count_mismatch")

    roster = base.route_roster(reference, match.group("date"), str(route)) if route else None
    station_mismatches = 0
    storable = 0
    for row in buses:
        if not isinstance(row, Mapping):
            result["errors"].append("invalid_record_row")
            continue
        stop_order = base.integer(row.get("stationSeq"))
        stop_id = base.scalar_text(row.get("stationId"))
        state = base.integer(row.get("stateCd"))
        passed = stop_order - 1 if stop_order is not None and state == 1 else stop_order
        if stop_order is not None and stop_id is not None and state in {0, 1, 2} and passed is not None and passed >= 0:
            storable += 1
        if roster is not None and stop_order in roster and roster[stop_order] != stop_id:
            station_mismatches += 1
    result["rds_storable_observations"] = storable
    if station_mismatches:
        result["quarantined"] = True

    raw_node = document.get("raw_response") if isinstance(document.get("raw_response"), Mapping) else {}
    raw_reference = raw_node.get("s3_key") if isinstance(raw_node.get("s3_key"), str) else None
    result["raw_reference"] = raw_reference
    result["raw_expected"] = raw_reference is not None
    expected = base.expected_raw_key(match)
    if raw_reference is None:
        if http.get("response_bytes") != 0 or document.get("response_envelope") is not None:
            result["errors"].append("invalid_raw_less_record")
    elif raw_reference != expected:
        result["errors"].append("raw_reference_path_mismatch")
    else:
        raw_info = raw_by_key.get(raw_reference)
        if raw_info is None:
            result["errors"].append("missing_raw_document")
        else:
            raw_body, metadata = get_body(client, bucket, raw_info)
            if raw_body is None:
                result["errors"].append("raw_read_or_snapshot_mismatch")
            else:
                result["raw_bytes"] = len(raw_body)
                raw_sha = hashlib.sha256(raw_body).hexdigest()
                declared = (http.get("response_sha256"), raw_node.get("sha256"), metadata.get("sha256"))
                if any(value != raw_sha for value in declared):
                    result["errors"].append("raw_hash_mismatch")
                if http.get("response_bytes") != len(raw_body):
                    result["errors"].append("raw_byte_count_mismatch")
                try:
                    raw_document = json.loads(raw_body.decode("utf-8-sig"))
                except Exception:
                    raw_document = None
                if raw_document != document.get("response_envelope"):
                    result["errors"].append("raw_envelope_mismatch")
                rows = base.raw_rows(raw_document)
                if len(rows) != len(buses) or any(
                    not isinstance(normalized, Mapping)
                    or any(normalized.get(field) != raw.get(field) for field in base.BUS_FIELDS)
                    for normalized, raw in zip(buses, rows)
                ):
                    result["errors"].append("raw_record_row_mismatch")

    record_id = document.get("record_id")
    response_sha = (
        document.get("http", {}).get("response_sha256")
        if isinstance(document.get("http"), Mapping)
        else None
    )
    route_id = base.scalar_text(route_node.get("route_id"))
    round_index = base.integer(
        document.get("collection", {}).get("round_index")
        if isinstance(document.get("collection"), Mapping)
        else None
    )
    result["identity"] = base.digest_text(
        base.SEMANTIC_BATCH_DOMAIN,
        base.SOURCE_ACCOUNT,
        route_id,
        base.iso_utc(started),
        base.iso_utc(observed),
        response_sha,
        round_index,
        record_id,
    )
    return result


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output already exists")
    authorities = parse_authorities(args)
    reference, protocol_sha = base.load_route_reference(args.route_reference_protocol)
    client = boto3.Session(profile_name=args.profile, region_name=args.region).client("s3")
    cutoff = datetime.now(UTC)
    records, raw = list_partition(client, args.bucket, args.partition_date, cutoff)
    before_digest = base.inventory_digest((*records, *raw))
    raw_by_key = {item.key: item for item in raw}
    raw_references: collections.Counter[str] = collections.Counter()
    segments = {
        "source_catch_up": segment_template(),
        "target_authority_overlap": segment_template(),
        "unknown": segment_template(),
    }
    per_route_segments = {
        route: {
            "source_catch_up": segment_template(),
            "target_authority_overlap": segment_template(),
            "unknown": segment_template(),
        }
        for route in ("1650", "3330")
    }
    errors = collections.Counter()
    identities: dict[str, collections.Counter[bytes]] = {
        "source_catch_up": collections.Counter(),
        "target_authority_overlap": collections.Counter(),
    }
    route_identities = {
        route: {
            "source_catch_up": collections.Counter(),
            "target_authority_overlap": collections.Counter(),
        }
        for route in ("1650", "3330")
    }

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [
            executor.submit(audit_one, client, args.bucket, info, raw_by_key, reference, authorities)
            for info in records
        ]
        for future in futures:
            try:
                result = future.result()
            except Exception:
                errors["unexpected_audit_failure"] += 1
                continue
            segment = segments[result["segment"]]
            route = str(result["route"] or "")
            selected_segments = [segment]
            if route in per_route_segments:
                selected_segments.append(per_route_segments[route][result["segment"]])
            for selected in selected_segments:
                selected["record_documents"] += 1
                selected["provider_observations"] += result["provider_observations"]
                selected["rds_storable_observations"] += result["rds_storable_observations"]
                selected["record_bytes"] += result["record_bytes"]
                selected["raw_bytes_referenced"] += result["raw_bytes"]
                if result["raw_expected"]:
                    selected["raw_documents_referenced"] += 1
                else:
                    selected["raw_less_records"] += 1
            if result["raw_reference"] is not None:
                raw_references[result["raw_reference"]] += 1
            for selected in selected_segments:
                for key in (
                    "by_route",
                    "by_classification",
                    "by_schema",
                    "by_http_status",
                    "by_api_result_code",
                    "by_mapped_outcome",
                    "by_mapped_failure_code",
                ):
                    value_name = {
                        "by_route": result["route"],
                        "by_classification": result["classification"],
                        "by_schema": result["schema"],
                        "by_http_status": result["http_status"],
                        "by_api_result_code": result["api_result_code"],
                        "by_mapped_outcome": result["mapped_outcome"],
                        "by_mapped_failure_code": result["mapped_failure_code"],
                    }[key]
                    if value_name is not None:
                        value = str(value_name)
                        selected[key][value] = selected[key].get(value, 0) + 1
                if result["observed_at"] is not None:
                    update_time_range(selected, result["observed_at"])
                    classification_key = str(result["classification"] or "<missing>")
                    classification_range = selected["classification_time_ranges"].setdefault(
                        classification_key,
                        {"records": 0, "first_response_received_at_utc": None, "last_response_received_at_utc": None},
                    )
                    classification_range["records"] += 1
                    update_time_range(classification_range, result["observed_at"])
                if result["quarantined"]:
                    selected["quarantined_records"] += 1
                    selected["quarantined_observations"] += result["provider_observations"]
                elif not result["errors"]:
                    selected["accepted_record_documents"] += 1
                    selected["accepted_rds_storable_observations"] += result["rds_storable_observations"]
                    if result["raw_expected"]:
                        selected["accepted_raw_documents"] += 1
                    else:
                        selected["accepted_raw_less_records"] += 1
            for code in result["errors"]:
                errors[code] += 1
            identity = result["identity"]
            if identity is not None and result["segment"] in identities:
                identities[result["segment"]][identity] += 1
                if route in route_identities:
                    route_identities[route][result["segment"]][identity] += 1

    after_cutoff = datetime.now(UTC)
    records_after, raw_after = list_partition(client, args.bucket, args.partition_date, cutoff)
    after_digest = base.inventory_digest((*records_after, *raw_after))
    orphan_raw = len(set(raw_by_key) - set(raw_references))
    duplicate_raw_refs = sum(count - 1 for count in raw_references.values() if count > 1)
    for segment in segments.values():
        for key in (
            "by_route", "by_classification", "by_schema", "by_http_status",
            "by_api_result_code", "by_mapped_outcome", "by_mapped_failure_code",
            "classification_time_ranges",
        ):
            segment[key] = dict(sorted(segment[key].items()))
    for route_segments in per_route_segments.values():
        for segment in route_segments.values():
            for key in (
                "by_route", "by_classification", "by_schema", "by_http_status",
                "by_api_result_code", "by_mapped_outcome", "by_mapped_failure_code",
                "classification_time_ranges",
            ):
                segment[key] = dict(sorted(segment[key].items()))

    receipt = {
        "schema_version": "salmonbus-continuity-window-audit-v2",
        "source_account": base.SOURCE_ACCOUNT,
        "partition_date_kst": args.partition_date,
        "target_authority_from_utc": (
            iso_exact(next(iter(authorities.values())))
            if len(set(authorities.values())) == 1
            else None
        ),
        "target_authority_by_route_utc": {
            route: iso_exact(authority) for route, authority in sorted(authorities.items())
        },
        "authority_policy": "RDS_ROUTE_VERSION_VALID_FROM_CONSERVATIVE_NO_LIVE_ROW_OVERLAP",
        "observed_at_utc": base.iso_utc(cutoff),
        "completed_at_utc": base.iso_utc(after_cutoff),
        "aws_operations": ["s3:ListBucket", "s3:GetObject"],
        "inventory": {
            "record_documents": len(records),
            "raw_documents": len(raw),
            "bytes": sum(item.size for item in (*records, *raw)),
            "record_last_modified_utc": iso_exact(max(item.last_modified for item in records)) if records else None,
            "raw_last_modified_utc": iso_exact(max(item.last_modified for item in raw)) if raw else None,
            "last_modified_utc": iso_exact(
                max(item.last_modified for item in (*records, *raw))
            ) if records or raw else None,
            "manifest_sha256_before": before_digest,
            "manifest_sha256_after": after_digest,
            "same_cutoff_stable": before_digest == after_digest,
            "unreferenced_raw_documents": orphan_raw,
            "duplicate_raw_reference_extras": duplicate_raw_refs,
        },
        "segments": segments,
        "segments_by_route": per_route_segments,
        "semantic_identity": {
            name: base.collision_summary(counter, sum(counter.values()))
            for name, counter in sorted(identities.items())
        },
        "semantic_identity_by_route": {
            route: {
                name: base.collision_summary(counter, sum(counter.values()))
                for name, counter in sorted(counters.items())
            }
            for route, counters in sorted(route_identities.items())
        },
        "record_errors": dict(sorted(errors.items())),
        "route_reference_protocol_sha256": protocol_sha,
        "privacy": {
            "row_level_material_persisted": False,
            "object_keys_emitted": False,
            "original_vehicle_ids_emitted": False,
            "plate_values_emitted": False,
            "source_hmac_values_emitted": False,
            "credentials_or_environment_values_emitted": False,
        },
    }
    args.output.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "source_catch_up_records": segments["source_catch_up"]["accepted_record_documents"],
                "target_overlap_records": segments["target_authority_overlap"]["record_documents"],
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
        raise SystemExit("continuity audit failed without emitting source identifiers") from None
