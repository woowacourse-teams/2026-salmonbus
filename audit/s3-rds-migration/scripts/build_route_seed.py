#!/usr/bin/env python3
"""Build sanitized 1650 route-reference evidence from pinned GBIS caches."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import subprocess
from pathlib import Path
from typing import Any


ANALYSIS_COMMIT = "c5ff99da81ed0af9916bd5aa5115d89f49258e2c"
CACHE_ORIGIN_COMMIT = "10f53632054c26a81c9d756bca5a9aad81d9ce3f"
CACHE_PATH = "source/rule5/lab/data/routes.json"
RUNTIME_PATH = "server/runtime/src/salmonbus_runtime/resources/route_reference.json"
PROTOCOL_PATH = "server/evaluator/current_public/evaluation/protocol.json"
EXPECTED_CACHE_SHA256 = "12c7bd562af13f1bdb87895254cd8a1651147a1f98729750f17f7d43e046f851"
EXPECTED_RUNTIME_SHA256 = "e59a6dd1e92ac5c788374704704d76785455f91d51c94f6e5f0753fc1a48cfd6"
EXPECTED_PROTOCOL_SHA256 = "d6c5588b02eb4e7f80115af20293ccb23e8c5dbb060da90e64a119559d2084be"
EXPECTED_ORDER_ID_DIGEST = "24a6ed132cb5b07cac03677d3e462cc11864cc7f68b12fd6fe1780c1fa9e3b71"
EXPECTED_CONTENT_DIGEST = "f6b5a3b0fabd731dbbab36054826be26714061c42d66b862eac60b191b4b51bc"
ROUTE_NAME = "1650"
PUBLIC_ROUTE_ID = "234000050"
TIME = re.compile(r"^(?:[01][0-9]|2[0-3]):[0-5][0-9]$")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("analysis_repository", type=Path)
    parser.add_argument("output", type=Path)
    return parser.parse_args()


def git_bytes(repository: Path, commit: str, path: str) -> bytes:
    return subprocess.check_output(
        ["git", "-C", str(repository), "show", f"{commit}:{path}"],
        stderr=subprocess.DEVNULL,
    )


def add_length_prefixed(digest: Any, value: Any) -> None:
    encoded = str(value).encode("utf-8")
    digest.update(struct.pack(">I", len(encoded)))
    digest.update(encoded)


def order_id_digest(stops: list[dict[str, Any]], id_field: str) -> str:
    digest = hashlib.sha256()
    for stop in stops:
        add_length_prefixed(digest, stop["sequence"])
        add_length_prefixed(digest, stop[id_field])
    return digest.hexdigest()


def content_digest(turn_sequence: int, stops: list[dict[str, Any]], id_field: str) -> str:
    digest = hashlib.sha256()
    add_length_prefixed(digest, turn_sequence)
    for stop in stops:
        add_length_prefixed(digest, stop["sequence"])
        add_length_prefixed(digest, stop[id_field])
        add_length_prefixed(digest, stop["name"])
    return digest.hexdigest()


def main() -> int:
    args = arguments()
    repository = args.analysis_repository.expanduser().resolve()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output already exists")
    cache_body = git_bytes(repository, CACHE_ORIGIN_COMMIT, CACHE_PATH)
    runtime_body = git_bytes(repository, ANALYSIS_COMMIT, RUNTIME_PATH)
    protocol_body = git_bytes(repository, ANALYSIS_COMMIT, PROTOCOL_PATH)
    if hashlib.sha256(cache_body).hexdigest() != EXPECTED_CACHE_SHA256:
        raise SystemExit("cached route source digest mismatch")
    if hashlib.sha256(runtime_body).hexdigest() != EXPECTED_RUNTIME_SHA256:
        raise SystemExit("runtime route source digest mismatch")
    if hashlib.sha256(protocol_body).hexdigest() != EXPECTED_PROTOCOL_SHA256:
        raise SystemExit("protocol digest mismatch")

    cache = json.loads(cache_body)[PUBLIC_ROUTE_ID]
    runtime_document = json.loads(runtime_body)
    runtime = runtime_document["routes"][ROUTE_NAME]
    protocol = json.loads(protocol_body)
    reference_version = protocol["route_reference"]["versions"][0]
    protocol_route = reference_version["routes"][ROUTE_NAME]
    stops = cache["stops"]

    if len(stops) != 89 or [stop["sequence"] for stop in stops] != list(range(1, 90)):
        raise SystemExit("cached stop sequence mismatch")
    if cache["turnSequence"] != 44 or protocol_route["turn_station_seq"] != 44:
        raise SystemExit("turn sequence mismatch")
    if len({str(stop["stationId"]) for stop in stops}) != 89:
        raise SystemExit("stop identifier uniqueness mismatch")
    if any(not str(stop["name"]).strip() or len(str(stop["name"])) > 60 for stop in stops):
        raise SystemExit("stop name constraint mismatch")
    if any(len(str(stop["stationId"])) > 20 for stop in stops):
        raise SystemExit("stop identifier constraint mismatch")
    if any(stop["direction"] != ("UP" if stop["sequence"] <= 44 else "DOWN") for stop in stops):
        raise SystemExit("stop direction mismatch")
    if any(stop["boardingAllowed"] != (not str(stop["stationId"]).startswith("277")) for stop in stops):
        raise SystemExit("boarding policy mismatch")
    if sum(not stop["boardingAllowed"] for stop in stops) != 24:
        raise SystemExit("nonboarding count mismatch")

    mismatches = [
        stop["sequence"]
        for stop in stops
        if str(protocol_route["stations"].get(str(stop["sequence"]))) != str(stop["stationId"])
    ]
    if mismatches:
        raise SystemExit("cached route and protocol station identity mismatch")

    transformed_runtime = {
        "displayName": runtime["displayName"],
        "startStopName": runtime["startStopName"],
        "endStopName": runtime["endStopName"],
        "turnSequence": runtime["turnSequence"],
        "directions": runtime["directions"],
        "stops": [
            {
                "sequence": stop["sequence"],
                "stationId": stop["stopId"],
                "name": stop["name"],
                "direction": stop["direction"],
                "boardingAllowed": stop["boardingAllowed"],
            }
            for stop in runtime["stops"]
        ],
    }
    for field in ("displayName", "startStopName", "endStopName", "turnSequence", "directions", "stops"):
        if transformed_runtime[field] != cache[field]:
            raise SystemExit("independent cached route representations differ")

    order_digest = order_id_digest(stops, "stationId")
    full_digest = content_digest(cache["turnSequence"], stops, "stationId")
    if order_digest != EXPECTED_ORDER_ID_DIGEST or full_digest != EXPECTED_CONTENT_DIGEST:
        raise SystemExit("route digest mismatch")
    directions = {direction["id"]: direction for direction in cache["directions"]}
    if set(directions) != {"UP", "DOWN"}:
        raise SystemExit("route direction metadata mismatch")
    if any(
        TIME.fullmatch(str(directions[key][field])) is None
        for key in ("UP", "DOWN")
        for field in ("firstDepartureTime", "lastDepartureTime")
    ):
        raise SystemExit("timetable format mismatch")

    output = {
        "schema_version": "salmonbus-rds-route-seed-v1",
        "classification": "PUBLIC_ROUTE_REFERENCE_NO_PRIVATE_VEHICLE_DATA",
        "status": "VALIDATED_REFERENCE_TARGET_VERSION_EXISTS_NO_INSERT",
        "artifact_role": "REFERENCE_EVIDENCE_FOR_FAIL_CLOSED_TARGET_PRECONDITION",
        "target_insert_allowed": False,
        "source": {
            "authority": runtime_document["source"],
            "analysis_commit": ANALYSIS_COMMIT,
            "cache_origin_commit": CACHE_ORIGIN_COMMIT,
            "cache_path": CACHE_PATH,
            "cache_sha256": EXPECTED_CACHE_SHA256,
            "runtime_reference_path": RUNTIME_PATH,
            "runtime_reference_sha256": EXPECTED_RUNTIME_SHA256,
            "protocol_path": PROTOCOL_PATH,
            "protocol_sha256": EXPECTED_PROTOCOL_SHA256,
            "reference_version_id": reference_version["route_reference_version_id"],
            "external_api_calls_for_this_audit": 0,
        },
        "route": {
            "public_route_id": PUBLIC_ROUTE_ID,
            "source_id": "GBIS",
            "source_route_id": PUBLIC_ROUTE_ID,
            "display_name": cache["displayName"],
            "start_stop_name": cache["startStopName"],
            "end_stop_name": cache["endStopName"],
        },
        "route_version": {
            "target_route_id": 2,
            "target_route_version_id": 2,
            "turn_sequence": cache["turnSequence"],
            "up_first_departure_time": directions["UP"]["firstDepartureTime"],
            "up_last_departure_time": directions["UP"]["lastDepartureTime"],
            "down_first_departure_time": directions["DOWN"]["firstDepartureTime"],
            "down_last_departure_time": directions["DOWN"]["lastDepartureTime"],
            "content_digest": full_digest,
            "target_valid_from_before": "2026-09-02T12:49:33.041299Z",
            "recommended_valid_from_after": "2026-08-14T07:38:46.604Z",
            "target_valid_to_remains": None,
            "accepted_source_earliest_response": "2026-08-14T07:38:46.604Z",
            "extension_before_accepted_source_forbidden": True,
            "identity_fields": [
                "route.public_route_id",
                "route_version.valid_from",
                "route_version.valid_to",
                "route_version.content_digest",
            ],
            "target_operation": "EXTEND_EXISTING_ROUTE_VERSION_VALID_FROM_ONLY",
            "validity_status": "PLANNED_REQUIRES_EXACT_TARGET_PRECONDITION_AND_WRITE_APPROVAL",
        },
        "route_stops": [
            {
                "stop_order": stop["sequence"],
                "stop_id": str(stop["stationId"]),
                "name": stop["name"],
                "direction": stop["direction"],
                "boarding_allowed": stop["boardingAllowed"],
            }
            for stop in stops
        ],
        "validation": {
            "stop_count": len(stops),
            "continuous_stop_orders": True,
            "unique_stop_ids": len({str(stop["stationId"]) for stop in stops}),
            "turn_sequence": cache["turnSequence"],
            "up_stops": sum(stop["direction"] == "UP" for stop in stops),
            "down_stops": sum(stop["direction"] == "DOWN" for stop in stops),
            "nonboarding_stops": sum(not stop["boardingAllowed"] for stop in stops),
            "boarding_policy": "not stop_id startsWith 277",
            "boarding_policy_mismatches": 0,
            "protocol_station_mismatches": len(mismatches),
            "first_protocol_station_mismatch": mismatches[0] if mismatches else None,
            "independent_cached_representation_mismatches": 0,
            "order_id_digest": order_digest,
            "dev_content_digest": full_digest,
            "maximum_stop_name_length": max(len(str(stop["name"])) for stop in stops),
        },
        "privacy": {
            "contains_vehicle_ids": False,
            "contains_plate_values": False,
            "contains_hmac_or_pseudonym_values": False,
            "contains_credentials": False,
            "contains_raw_observation_rows": False,
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "api_calls": 0,
                "content_digest": full_digest,
                "first_mismatch": None,
                "order_id_digest": order_digest,
                "route": ROUTE_NAME,
                "stops": len(stops),
                "status": "succeeded",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
