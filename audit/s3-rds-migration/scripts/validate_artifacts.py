#!/usr/bin/env python3
"""Validate cross-artifact counts, schemas, identities, and privacy guards."""

from __future__ import annotations

import copy
import hashlib
import json
import re
import struct
from datetime import datetime
from pathlib import Path
from typing import Any, Iterable

import jsonschema


ROOT = Path(__file__).resolve().parents[1]
SOURCE_VALIDATION = ROOT / "source-validation-frozen.json"
INVENTORY = ROOT / "inventory.json"
CONTINUITY = ROOT / "continuity-window.json"
CONTINUITY_FINAL_1 = ROOT / "continuity-window-final-1.json"
CONTINUITY_FINAL_2 = ROOT / "continuity-window-final-2.json"
CUTOVER_READINESS = ROOT / "cutover-readiness.json"
SOURCE_FREEZE_1 = ROOT / "source-freeze-confirmation-1.json"
SOURCE_FREEZE_2 = ROOT / "source-freeze-confirmation-2.json"
SUMMARY = ROOT / "audit-summary.json"
MAPPING = ROOT / "field-mapping.json"
FIXTURE = ROOT / "acceptance-fixture.json"
ARCHIVE_SCHEMA = ROOT / "archive-record.schema.json"
MANIFEST_SCHEMA = ROOT / "archive-manifest.schema.json"
TEMP_GENERATION_SCHEMA = ROOT / "temp-generation-manifest.schema.json"
ROUTE_RECEIPT_SCHEMA = ROOT / "route-migration-receipt.schema.json"
TRAINER = ROOT / "trainer-read-contract.json"
SIZING = ROOT / "postgres-sizing.json"
PROVENANCE = ROOT / "provenance.json"
TEMP_DRY_RUN = ROOT / "scripts" / "temp_release_cleanup_dry_run.sql"
BOUNDARY_DRY_RUN = ROOT / "scripts" / "boundary_continuity_readonly.sql"
ROUTE_SEED = ROOT / "route-seed-1650.json"
ROUTE_MAPPING = ROOT / "route-mapping-summary.json"
TARGET_DEV_DELTA = ROOT / "target-dev-delta.json"
TARGET_PREFLIGHT = ROOT / "scripts" / "target_preflight_readonly.sql"

SOURCE_ACCOUNT = "827325854159"
TARGET_ACCOUNT = "843255971531"
TEMP_RELEASE = "salmonbus-d57370be9195520e"
TEMP_BUNDLE = "d57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a"
TEMP_CALCULATION = "seat-feature-contract-v4-1-2026-09-02"
TEMP_ACTIVATED = "2026-09-02T11:55:04.729493Z"
TARGET_DEV_COMMIT = "d856d10819bf1d018ad43fa63714cc348f1fc643"
ROUTE_AUTHORITIES = {
    "3330": "2026-09-02T10:27:51.330754Z",
    "1650": "2026-09-02T12:49:33.041299Z",
}
TERMINAL_FULL_MANIFEST = "ad7dca914792eb243008df549fef844e5a32d004a867d7c10c44b6c979b7fad8"
TERMINAL_PARTITION_MANIFEST = "f0decee3446e0e787532c9682bd3c6c627ccf9f10cdba9a992829345fdefa86e"
IMMUTABLE_BASE_MANIFEST = "db47305386c77fd6d28411ce09b5e1633a029027bc15d41c8201139fb9d535b9"


def load(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def strings(value: Any) -> Iterable[str]:
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for child in value.values():
            yield from strings(child)
    elif isinstance(value, list):
        for child in value:
            yield from strings(child)


def all_false(value: dict[str, Any], keys: Iterable[str]) -> bool:
    return all(value.get(key) is False for key in keys)


def instant(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def without_keys(document: dict[str, Any], *keys: str) -> dict[str, Any]:
    result = copy.deepcopy(document)
    for key in keys:
        result.pop(key, None)
    return result


def route_digest(turn_sequence: int | None, stops: list[dict[str, Any]], *, include_name: bool) -> str:
    digest = hashlib.sha256()

    def add(value: Any) -> None:
        encoded = str(value).encode("utf-8")
        digest.update(struct.pack(">I", len(encoded)))
        digest.update(encoded)

    if include_name:
        add(turn_sequence)
    for stop in stops:
        add(stop["stop_order"])
        add(stop["stop_id"])
        if include_name:
            add(stop["name"])
    return digest.hexdigest()


def validate_privacy(path: Path, document: Any) -> None:
    for value in strings(document):
        require(
            re.fullmatch(r"hmac-sha256:[0-9a-f]{64}", value) is None,
            f"source HMAC value in {path.name}",
        )
        require(
            re.match(r"^(records|raw)/route=", value) is None,
            f"private object key in {path.name}",
        )
        require("X-Amz-Signature=" not in value, f"presigned URL in {path.name}")
        require(
            re.search(r"(?:AKIA|ASIA)[A-Z0-9]{16}", value) is None,
            f"AWS access key pattern in {path.name}",
        )


def verify_sha256sums() -> None:
    sums_path = ROOT / "SHA256SUMS"
    if not sums_path.exists():
        return
    listed = set()
    for line in sums_path.read_text(encoding="utf-8").splitlines():
        if not line:
            continue
        digest, relative = line.split("  ", 1)
        require(relative not in listed, f"duplicate SHA-256 entry: {relative}")
        listed.add(relative)
        target = ROOT / relative
        require(target.is_file(), f"missing checksummed file: {relative}")
        actual = hashlib.sha256(target.read_bytes()).hexdigest()
        require(actual == digest, f"SHA-256 mismatch: {relative}")
    expected = {
        path.relative_to(ROOT).as_posix()
        for path in ROOT.rglob("*")
        if path.is_file() and path != sums_path
    }
    require(listed == expected, "SHA-256 file coverage drift")


def main() -> int:
    source = load(SOURCE_VALIDATION)
    inventory = load(INVENTORY)
    continuity = load(CONTINUITY)
    continuity_final_1 = load(CONTINUITY_FINAL_1)
    continuity_final_2 = load(CONTINUITY_FINAL_2)
    cutover = load(CUTOVER_READINESS)
    source_freeze_1 = load(SOURCE_FREEZE_1)
    source_freeze_2 = load(SOURCE_FREEZE_2)
    summary = load(SUMMARY)
    mapping = load(MAPPING)
    fixture = load(FIXTURE)
    archive_schema = load(ARCHIVE_SCHEMA)
    manifest_schema = load(MANIFEST_SCHEMA)
    temp_generation_schema = load(TEMP_GENERATION_SCHEMA)
    route_receipt_schema = load(ROUTE_RECEIPT_SCHEMA)
    trainer = load(TRAINER)
    sizing = load(SIZING)
    provenance = load(PROVENANCE)
    route_seed = load(ROUTE_SEED)
    route_mapping = load(ROUTE_MAPPING)
    target_dev_delta = load(TARGET_DEV_DELTA)

    for schema in (archive_schema, manifest_schema, temp_generation_schema, route_receipt_schema):
        jsonschema.Draft202012Validator.check_schema(schema)
    archive_validator = jsonschema.Draft202012Validator(
        archive_schema,
        format_checker=jsonschema.FormatChecker(),
    )
    injected_records = []
    for record_index, stored_record in enumerate(fixture["archiveRecordsWithRuntimeSensitiveValueOmitted"]):
        require(
            all("vehicle_id" not in observation for observation in stored_record["observations"]),
            "fixture persisted a vehicle_id value",
        )
        record = copy.deepcopy(stored_record)
        for observation_index, observation in enumerate(record["observations"]):
            observation["vehicle_id"] = f"runtime-only-{record_index}-{observation_index}"
        archive_validator.validate(record)
        injected_records.append(record)
    jsonschema.Draft202012Validator(
        temp_generation_schema,
        format_checker=jsonschema.FormatChecker(),
    ).validate(fixture["validTemporaryGenerationManifest"])
    jsonschema.Draft202012Validator(
        manifest_schema,
        format_checker=jsonschema.FormatChecker(),
    ).validate(fixture["validLateDeltaManifest"])
    jsonschema.Draft202012Validator(
        route_receipt_schema,
        format_checker=jsonschema.FormatChecker(),
    ).validate(fixture["validRouteMigrationReceipt"])
    delta = fixture["validLateDeltaManifest"]
    require(delta["totals"]["shards"] == len(delta["shards"]), "delta shard total mismatch")
    for field in ("compressed_bytes", "uncompressed_bytes", "record_documents", "observation_rows"):
        require(
            delta["totals"][field] == sum(shard[field] for shard in delta["shards"]),
            f"delta total mismatch: {field}",
        )
    sensitive_mutation = copy.deepcopy(injected_records[0])
    sensitive_mutation["observations"][0]["plate_number"] = "runtime-only-forbidden"
    require(
        not archive_validator.is_valid(sensitive_mutation),
        "archive schema accepted a forbidden plate field",
    )
    missing_vehicle = copy.deepcopy(injected_records[0])
    del missing_vehicle["observations"][0]["vehicle_id"]
    require(not archive_validator.is_valid(missing_vehicle), "archive schema accepted missing vehicle_id")

    require(mapping["accounts"]["sourceAccount"] == SOURCE_ACCOUNT, "source account drift")
    require(mapping["accounts"]["targetAccount"] == TARGET_ACCOUNT, "target account drift")
    require(mapping["accounts"]["targetAccess"]["attempted"] is False, "target access drift")
    require(mapping["transfer"]["executionStatus"] == "DESIGN_ONLY_NO_REMOTE_WRITE", "remote write drift")
    require(mapping["transfer"]["primary"]["mode"] == "RESUMABLE_RSYNC_OVER_SSH", "primary transfer drift")
    require(mapping["transfer"]["fallback"]["mode"] == "SHORT_LIVED_PRESIGNED_HTTPS_GET", "fallback drift")
    require(mapping["encryption"]["sourceBucketObservedDefault"]["mode"] == "SSE-S3", "source encryption drift")
    require(mapping["basis"]["targetDevCommit"] == TARGET_DEV_COMMIT, "mapping target-dev drift")
    require(summary["targetDevCommit"] == TARGET_DEV_COMMIT, "summary target-dev drift")
    require(trainer["targetDevCommit"] == TARGET_DEV_COMMIT, "trainer target-dev drift")
    require(route_mapping["targetDevCommit"] == TARGET_DEV_COMMIT, "route target-dev drift")
    require(target_dev_delta["targetDevCommit"] == TARGET_DEV_COMMIT, "delta target-dev drift")
    require(len(target_dev_delta["changedFiles"]) == 4, "unexpected target-dev changed-file count")
    require(
        target_dev_delta["effectiveContractImpact"]["fieldMappingChangesRequired"] is False,
        "unexpected target-dev field mapping impact",
    )
    require(
        target_dev_delta["effectiveContractImpact"]["routeAcceptanceChangesRequired"] is True,
        "missing target-dev route acceptance impact",
    )
    boundary_evidence = summary["continuityWindow"]["boundaryEvidence"]
    require(boundary_evidence["executableScope"] == "ROUTE_SPECIFIC_ONLY", "summary boundary scope drift")
    require(
        boundary_evidence["historicalBoundaryEvidenceLocation"] == "AUDIT.md",
        "historical boundary evidence location drift",
    )
    require(
        mapping["authorityBoundary"]["executableBoundaryScope"] == "ROUTE_SPECIFIC_ONLY",
        "mapping boundary scope drift",
    )
    trainer_boundary = trainer["history"]["continuityMode"]["boundary"]
    require(trainer_boundary["executableScope"] == "ROUTE_SPECIFIC_ONLY", "trainer boundary scope drift")
    acceptance_ids = {item["id"] for item in fixture["databaseAcceptance"]}
    require("route-specific-boundary-readonly-v2" in acceptance_ids, "fixture route boundary v2 missing")
    require("legacy-global-boundary-reconciled" not in acceptance_ids, "fixture retains global boundary")
    vehicle_mapping = next(
        item for item in mapping["mappings"] if item["sourcePaths"] == ["$.buses[].vehId"]
    )
    require(vehicle_mapping["target"] == "vehicle_observation.vehicle_id", "vehicle target drift")
    require(vehicle_mapping["disposition"] == "MIGRATE", "vehicle disposition drift")
    require(vehicle_mapping["privacyClassification"] == "PRIVATE_SENSITIVE_INTERNAL", "vehicle privacy drift")
    semantic_mapping = next(
        item
        for item in mapping["mappings"]
        if item.get("target") == "observation_batch.attempt_key and migration_source_record.semantic_batch_digest"
    )
    require(
        all("vehId" not in path and "plate" not in path.lower() for path in semantic_mapping["sourcePaths"]),
        "semantic digest includes a reversible private identifier field",
    )
    require(
        all(item["name"] != "vehicle_observation.vehicle_continuity_digest" for item in mapping["requiredSchemaAdditions"]),
        "obsolete vehicle continuity blocker remains",
    )
    require(provenance["sourceRuntime"]["account"] == SOURCE_ACCOUNT, "provenance source drift")
    require(provenance["targetEvidence"]["account"] == TARGET_ACCOUNT, "provenance target drift")
    require(provenance["targetEvidence"]["queriedByThisAudit"] is False, "provenance target access drift")
    provenance_runs = {run["artifact"]: run for run in provenance["runs"]}
    require(
        provenance_runs["source-freeze-confirmation-1.json"]["observedAtUtc"]
        == source_freeze_1["observed_at_utc"],
        "provenance list receipt 1 drift",
    )
    require(
        provenance_runs["source-freeze-confirmation-2.json"]["observedAtUtc"]
        == source_freeze_2["observed_at_utc"],
        "provenance list receipt 2 drift",
    )
    require(
        provenance_runs["source-freeze-confirmation-1.json"]["awsOperations"]
        == source_freeze_1["aws_operations"]
        and provenance_runs["source-freeze-confirmation-2.json"]["awsOperations"]
        == source_freeze_2["aws_operations"],
        "provenance list operation drift",
    )
    for artifact, receipt in (
        ("continuity-window-final-1.json", continuity_final_1),
        ("continuity-window-final-2.json", continuity_final_2),
    ):
        run = provenance_runs[artifact]
        require(run["observedAtUtc"] == receipt["observed_at_utc"], f"{artifact} observed time drift")
        require(run["completedAtUtc"] == receipt["completed_at_utc"], f"{artifact} completion time drift")
        require(run["awsOperations"] == receipt["aws_operations"], f"{artifact} operation drift")
        require(run["sourceMutationPerformed"] is False, f"{artifact} source mutation drift")
    preflight_run = provenance_runs["scripts/target_preflight_readonly.sql"]
    require(preflight_run["targetQueried"] is False, "provenance target preflight access drift")
    require(preflight_run["transactionReadOnly"] is True, "provenance preflight transaction drift")
    require(preflight_run["result"] == "PASSED", "provenance preflight result drift")
    boundary_run = provenance_runs["scripts/boundary_continuity_readonly.sql"]
    require(boundary_run["targetQueried"] is False, "provenance target boundary access drift")
    require(boundary_run["transactionReadOnly"] is True, "provenance boundary transaction drift")
    require(boundary_run["routesValidated"] == ["3330", "1650"], "provenance boundary route drift")
    require(boundary_run["result"] == "PASSED", "provenance boundary result drift")
    require(route_seed["validation"]["stop_count"] == 89, "1650 stop count drift")
    route_stops = route_seed["route_stops"]
    require([item["stop_order"] for item in route_stops] == list(range(1, 90)), "1650 order drift")
    require(
        all(item["direction"] == ("UP" if item["stop_order"] <= 44 else "DOWN") for item in route_stops),
        "1650 direction drift",
    )
    require(
        all(item["boarding_allowed"] == (not item["stop_id"].startswith("277")) for item in route_stops),
        "1650 boarding policy drift",
    )
    require(route_seed["validation"]["protocol_station_mismatches"] == 0, "1650 protocol mismatch")
    require(route_seed["validation"]["first_protocol_station_mismatch"] is None, "1650 first mismatch drift")
    require(
        route_seed["validation"]["dev_content_digest"]
        == "f6b5a3b0fabd731dbbab36054826be26714061c42d66b862eac60b191b4b51bc",
        "1650 content digest drift",
    )
    require(
        route_digest(44, route_stops, include_name=False)
        == "24a6ed132cb5b07cac03677d3e462cc11864cc7f68b12fd6fe1780c1fa9e3b71",
        "1650 order/id digest recomputation drift",
    )
    require(
        route_digest(44, route_stops, include_name=True)
        == "f6b5a3b0fabd731dbbab36054826be26714061c42d66b862eac60b191b4b51bc",
        "1650 content digest recomputation drift",
    )
    require(route_seed["source"]["external_api_calls_for_this_audit"] == 0, "unexpected route API call")
    require(all(value is False for value in route_seed["privacy"].values()), "route seed privacy drift")
    require(route_mapping["routes"]["3330"]["sourceComparison"]["orderIdMismatches"] == 0, "3330 route mismatch")
    require(route_mapping["routes"]["1650"]["sourceComparison"]["protocolMismatches"] == 0, "1650 route mismatch")
    mismatch = route_mapping["stationMismatchQuarantine"]
    require(mismatch["mismatchRows"] == 27, "station mismatch count drift")
    require(mismatch["acceptedObservationDenominator"] == 2324399, "station mismatch denominator drift")
    require(abs(mismatch["rate"] - 27 / 2324399) < 5e-13, "station mismatch rate drift")

    target = mapping["targetBaseline"]
    require(target["rowCounts"]["observation_batch"] == 303, "target batch baseline drift")
    require(target["rowCounts"]["vehicle_observation"] == 8896, "target observation baseline drift")
    require(target["rowCounts"]["route"] == 1, "target route baseline drift")
    require(target["rowCounts"]["route_version"] == 1, "target route-version baseline drift")
    require(target["rowCounts"]["route_stop"] == 85, "target route-stop baseline drift")
    live_route_state = target["latestLiveRouteState"]
    require(
        (
            live_route_state["routeRows"],
            live_route_state["routeVersionRows"],
            live_route_state["routeStopRows"],
        )
        == (2, 2, 174),
        "latest live route-state drift",
    )
    require(live_route_state["queriedByThisAudit"] is False, "unexpected target route query")
    require(
        live_route_state["routes"]["3330"]
        == {
            "routeId": 1,
            "routeVersionId": 1,
            "validFromUtc": ROUTE_AUTHORITIES["3330"],
            "referenceExact": True,
        },
        "3330 live route-state drift",
    )
    require(
        live_route_state["routes"]["1650"]
        == {
            "routeId": 2,
            "routeVersionId": 2,
            "validFromUtc": ROUTE_AUTHORITIES["1650"],
            "referenceExact": True,
        },
        "1650 live route-state drift",
    )
    temporary = target["subsequentTemporaryDeployment"]
    require(temporary["modelDeploymentId"] == 1, "temporary deployment id drift")
    require(temporary["releaseId"] == TEMP_RELEASE, "temporary release drift")
    require(temporary["bundleDigest"] == TEMP_BUNDLE, "temporary bundle drift")
    require(temporary["calculationVersion"] == TEMP_CALCULATION, "temporary calculation drift")
    require(temporary["activatedAtUtc"] == TEMP_ACTIVATED, "temporary activation drift")
    carrier = mapping["modelDataExclusions"][0]["stopDemandCarrierContamination"]
    require(carrier["baselineRowsBeforeActivation"] == 0, "statistics baseline drift")
    require(carrier["carrierCalculationVersion"] == "observed-max-capacity-v1", "carrier calculation drift")

    totals = source["totals"]
    require(totals["inventory_record_documents"] == 142156, "base record inventory drift")
    require(totals["inventory_raw_documents"] == 142031, "base raw inventory drift")
    require(totals["accepted_record_documents"] == 142129, "base accepted record drift")
    require(totals["accepted_raw_documents"] == 142004, "base accepted raw drift")
    require(totals["accepted_raw_less_records"] == 125, "base raw-less drift")
    require(totals["accepted_observations"] == 2324399, "base observation drift")
    require(totals["invalid_records"] == 0, "source corruption detected")
    require(totals["station_mismatch_records"] == 27, "base quarantine drift")
    require(totals["repaired_partition_crossings"] == 4, "partition repair drift")

    aggregate = inventory["record_aggregate"]
    require(aggregate["record_documents"] == totals["inventory_record_documents"], "inventory record mismatch")
    require(
        aggregate["private_archive_eligible_records"] == totals["accepted_record_documents"],
        "eligible record mismatch",
    )
    require(
        aggregate["private_archive_eligible_observations"] == totals["accepted_observations"],
        "eligible observation mismatch",
    )
    require(aggregate["rds_excluded_observations"] == 0, "unexpected row normalization exclusion")
    require(aggregate["duplicate_vehicle_rows_within_batch"] == 0, "within-batch vehicle duplicate")
    require(inventory["record_errors"] == {}, "record errors present")
    require(inventory["record_raw_bijection"]["referenced_raw_missing"] == 0, "missing raw object")
    require(inventory["record_raw_bijection"]["unreferenced_raw_documents"] == 0, "orphan raw object")
    require(inventory["record_raw_bijection"]["duplicate_raw_reference_extras"] == 0, "duplicate raw reference")
    require(inventory["inventory"]["late_objects_after_next_day_00_15_kst"] == {}, "late base object")
    require(inventory["snapshot_reconciliation"]["immutable_data_same_cutoff_stable"] is True, "base snapshot moved")
    require(inventory["vehicle_identity_consistency"]["bidirectional_mapping_conflicts"] == 0, "vehicle identity conflict")
    require(inventory["natural_identity_candidates"]["semantic_batch_digest"]["collision_rate"] == 0.0, "semantic collision")
    require(inventory["natural_identity_candidates"]["source_record_id"]["collision_rate"] == 0.0, "record-id collision")
    private_archive = inventory["private_sensitive_archive_measurement"]
    require(private_archive["data_classification"] == "PRIVATE_SENSITIVE_NORMALIZED", "inventory archive class drift")
    require(private_archive["contains_vehicle_id"] is True, "inventory vehicle policy drift")
    require(private_archive["contains_plate_hmac_or_raw"] is False, "inventory forbidden-field drift")
    require(private_archive["uncompressed_bytes"] == 666925584, "private archive size drift")
    require(private_archive["zstd_level_3_bytes"] == 19589490, "private archive compression drift")
    require(sum(inventory["collector_schema_versions"].values()) == 142156, "schema count mismatch")
    require(
        sum(item["records"] for item in inventory["record_schemas"]) == 142156,
        "record shape count mismatch",
    )
    require(
        sum(
            values["record_documents"]
            for routes in inventory["daily_route_distribution"].values()
            for values in routes.values()
        ) == 142156,
        "daily/route record count mismatch",
    )
    for field in ("vehId", "plateNo", "stationId", "stationSeq", "stateCd"):
        require(
            inventory["bus_field_profiles"][field]["present"] == aggregate["provider_observations"],
            f"field coverage mismatch: {field}",
        )

    require(continuity["schema_version"] == "salmonbus-continuity-window-audit-v2", "continuity schema drift")
    require(continuity["inventory"]["same_cutoff_stable"] is True, "continuity snapshot moved")
    require(continuity["record_errors"] == {}, "continuity record errors present")
    require(continuity["target_authority_from_utc"] is None, "obsolete global authority remains")
    require(continuity["target_authority_by_route_utc"] == ROUTE_AUTHORITIES, "route authority drift")
    require(continuity["authority_policy"] == "RDS_ROUTE_VERSION_VALID_FROM_CONSERVATIVE_NO_LIVE_ROW_OVERLAP", "authority policy drift")
    catch_up = continuity["segments"]["source_catch_up"]
    overlap = continuity["segments"]["target_authority_overlap"]
    require(catch_up["record_documents"] == 7065, "catch-up inventory record drift")
    require(catch_up["accepted_record_documents"] == 7064, "catch-up record drift")
    require(catch_up["accepted_raw_documents"] == 7062, "catch-up raw drift")
    require(catch_up["accepted_raw_less_records"] == 2, "catch-up raw-less drift")
    require(catch_up["accepted_rds_storable_observations"] == 136909, "catch-up observation drift")
    require(catch_up["quarantined_records"] == 1, "catch-up quarantine drift")
    require(catch_up["quarantined_observations"] == 24, "catch-up quarantine observation drift")
    require(overlap["record_documents"] == 811, "overlap record drift")
    require(overlap["accepted_record_documents"] == 811, "overlap accepted record drift")
    require(overlap["accepted_rds_storable_observations"] == 14924, "overlap observation drift")
    require(overlap["by_http_status"].get("429") == 190, "HTTP 429 record drift")
    require(overlap["by_mapped_outcome"].get("FAILED_UPSTREAM") == 190, "quota outcome drift")
    expected_route_segments = {
        "3330": {
            "catch_up": (3249, 3247, 2, 58234, 0, 0),
            "overlap": (689, 14364, 95),
        },
        "1650": {
            "catch_up": (3815, 3815, 0, 78675, 1, 24),
            "overlap": (122, 560, 95),
        },
    }
    for route_name, expected in expected_route_segments.items():
        route_segments = continuity["segments_by_route"][route_name]
        route_catch_up = route_segments["source_catch_up"]
        route_overlap = route_segments["target_authority_overlap"]
        require(
            (
                route_catch_up["accepted_record_documents"],
                route_catch_up["accepted_raw_documents"],
                route_catch_up["accepted_raw_less_records"],
                route_catch_up["accepted_rds_storable_observations"],
                route_catch_up["quarantined_records"],
                route_catch_up["quarantined_observations"],
            )
            == expected["catch_up"],
            f"{route_name} catch-up drift",
        )
        require(
            (
                route_overlap["accepted_record_documents"],
                route_overlap["accepted_rds_storable_observations"],
                route_overlap["by_http_status"].get("429"),
            )
            == expected["overlap"],
            f"{route_name} overlap drift",
        )
        require(route_segments["unknown"]["record_documents"] == 0, f"{route_name} unknown segment")
        for identity in continuity["semantic_identity_by_route"][route_name].values():
            require(identity["collision_rate"] == 0.0, f"{route_name} identity collision")
    require(
        catch_up["accepted_record_documents"]
        == sum(
            continuity["segments_by_route"][route]["source_catch_up"]["accepted_record_documents"]
            for route in ROUTE_AUTHORITIES
        ),
        "catch-up route sum mismatch",
    )
    require(
        overlap["accepted_rds_storable_observations"]
        == sum(
            continuity["segments_by_route"][route]["target_authority_overlap"]["accepted_rds_storable_observations"]
            for route in ROUTE_AUTHORITIES
        ),
        "overlap route sum mismatch",
    )
    require(continuity["semantic_identity"]["source_catch_up"]["collision_rate"] == 0.0, "catch-up identity collision")

    maximum = summary["maximumAutomaticImport"]
    require(maximum["authorityByRouteUtcInclusive"] == ROUTE_AUTHORITIES, "summary route authority drift")
    require(maximum["importRows"]["observation_batch"] == 142129 + 7064, "maximum batch formula")
    require(maximum["importRows"]["vehicle_observation"] == 2324399 + 136909, "maximum observation formula")
    require(
        maximum["importRows"]["byRoute"]
        == {
            "3330": {"observation_batch": 74304, "vehicle_observation": 1175694},
            "1650": {"observation_batch": 74889, "vehicle_observation": 1285614},
        },
        "maximum route import drift",
    )
    require(
        maximum["importRows"]["vehicle_id_non_null"] == maximum["importRows"]["vehicle_observation"],
        "vehicle_id migration count mismatch",
    )
    require(maximum["importRows"]["plate_number_non_null"] == 0, "plate backfill must be null")
    require(summary["archive"]["dataClassification"] == "PRIVATE_SENSITIVE_NORMALIZED", "archive class drift")
    require(summary["archive"]["handling"]["fileMode"] == "0600", "archive file mode drift")
    require(summary["archive"]["handling"]["directoryMode"] == "0700", "archive directory mode drift")
    require(
        summary["targetBaselineAndExpectedCounts"]["anchoredMinimumAfterImport"]["observation_batch"]
        == 303 + maximum["importRows"]["observation_batch"],
        "target batch projection mismatch",
    )
    require(
        summary["targetBaselineAndExpectedCounts"]["anchoredMinimumAfterImport"]["vehicle_observation"]
        == 8896 + maximum["importRows"]["vehicle_observation"],
        "target observation projection mismatch",
    )
    expected_target = summary["targetBaselineAndExpectedCounts"]["anchoredMinimumAfterImport"]
    require(
        (expected_target["route"], expected_target["route_version"], expected_target["route_stop"])
        == (2, 2, 174),
        "target route projection mismatch",
    )

    require(
        without_keys(source_freeze_1, "observed_at_utc")
        == without_keys(source_freeze_2, "observed_at_utc"),
        "terminal list receipts differ beyond observation time",
    )
    require(
        without_keys(continuity_final_1, "observed_at_utc", "completed_at_utc")
        == without_keys(continuity_final_2, "observed_at_utc", "completed_at_utc"),
        "terminal byte receipts differ beyond run time",
    )
    require(continuity == continuity_final_2, "canonical continuity is not final receipt 2")
    require(
        [source_freeze_1["observed_at_utc"], source_freeze_2["observed_at_utc"]]
        == ["2026-09-02T13:21:14.320Z", "2026-09-02T13:22:53.278Z"],
        "terminal list confirmation time drift",
    )
    require(
        [
            (continuity_final_1["observed_at_utc"], continuity_final_1["completed_at_utc"]),
            (continuity_final_2["observed_at_utc"], continuity_final_2["completed_at_utc"]),
        ]
        == [
            ("2026-09-02T13:21:57.421Z", "2026-09-02T13:22:34.926Z"),
            ("2026-09-02T13:23:40.988Z", "2026-09-02T13:24:20.855Z"),
        ],
        "terminal byte confirmation time drift",
    )
    for receipt in (source_freeze_1, source_freeze_2):
        require(receipt["source_account"] == SOURCE_ACCOUNT, "terminal list source drift")
        require(receipt["aws_operations"] == ["s3:ListBucket"], "terminal list operation drift")
        require(receipt["mutation_performed"] is False, "terminal list mutation recorded")
        require(receipt["late_base_objects_after_next_day_00_15_kst"] == {}, "terminal base late object")
        require(all_false(receipt["privacy"], receipt["privacy"].keys()), "terminal list privacy drift")
    terminal_full = source_freeze_2["terminal_source_through_partition"]
    terminal_partition = source_freeze_2["terminal_partition"]
    immutable_base = source_freeze_2["immutable_base"]
    require(
        (
            terminal_full["record_objects"],
            terminal_full["raw_objects"],
            terminal_full["objects"],
            terminal_full["bytes"],
            terminal_full["manifest_sha256"],
        )
        == (150032, 149905, 299937, 2346432060, TERMINAL_FULL_MANIFEST),
        "terminal full freeze drift",
    )
    require(
        (
            terminal_partition["record_objects"],
            terminal_partition["raw_objects"],
            terminal_partition["objects"],
            terminal_partition["bytes"],
            terminal_partition["manifest_sha256"],
        )
        == (7876, 7874, 15750, 140161072, TERMINAL_PARTITION_MANIFEST),
        "terminal partition freeze drift",
    )
    require(
        (
            immutable_base["record_objects"],
            immutable_base["raw_objects"],
            immutable_base["objects"],
            immutable_base["bytes"],
            immutable_base["manifest_sha256"],
        )
        == (142156, 142031, 284187, 2206270988, IMMUTABLE_BASE_MANIFEST),
        "immutable base freeze drift",
    )
    require(
        (
            continuity["inventory"]["record_documents"],
            continuity["inventory"]["raw_documents"],
            continuity["inventory"]["bytes"],
            continuity["inventory"]["manifest_sha256_after"],
        )
        == (
            terminal_partition["record_objects"],
            terminal_partition["raw_objects"],
            terminal_partition["bytes"],
            terminal_partition["manifest_sha256"],
        ),
        "terminal list/byte partition mismatch",
    )
    require(
        instant(continuity["inventory"]["last_modified_utc"])
        == instant(terminal_partition["last_modified_utc"]),
        "terminal partition LastModified mismatch",
    )
    confirmation_points = cutover["terminalFreeze"]["confirmationPoints"]
    require(
        confirmation_points
        == [
            {
                "listObservedAtUtc": source_freeze_1["observed_at_utc"],
                "byteAuditObservedAtUtc": continuity_final_1["observed_at_utc"],
                "byteAuditCompletedAtUtc": continuity_final_1["completed_at_utc"],
            },
            {
                "listObservedAtUtc": source_freeze_2["observed_at_utc"],
                "byteAuditObservedAtUtc": continuity_final_2["observed_at_utc"],
                "byteAuditCompletedAtUtc": continuity_final_2["completed_at_utc"],
            },
        ],
        "cutover confirmation points drift",
    )
    require(cutover["assessment"]["postDisableDecision"] == "TERMINAL_SOURCE_FREEZE_CONFIRMED", "cutover decision drift")
    require(cutover["assessment"]["remainingSourceCatchUpOpen"] is False, "cutover catch-up remains open")
    require(cutover["terminalFreeze"]["stableAcrossBothListPoints"] is True, "list freeze not stable")
    require(cutover["terminalFreeze"]["stableAcrossBothByteAudits"] is True, "byte freeze not stable")
    require(cutover["awsWritePerformedByAudit"] is False, "audit AWS write recorded")
    require(cutover["remoteWritePerformedByAudit"] is False, "audit remote write recorded")
    require(cutover["routeAuthorityClosure"]["authorityByRouteUtc"] == ROUTE_AUTHORITIES, "cutover route authority drift")
    require(cutover["routeAuthorityClosure"]["automaticImport"]["observationBatch"] == maximum["importRows"]["observation_batch"], "cutover batch import drift")
    require(cutover["routeAuthorityClosure"]["automaticImport"]["vehicleObservation"] == maximum["importRows"]["vehicle_observation"], "cutover observation import drift")
    require(cutover["routeAuthorityClosure"]["terminalOverlapExcluded"]["recordDocuments"] == overlap["accepted_record_documents"], "cutover overlap record drift")
    require(cutover["routeAuthorityClosure"]["terminalOverlapExcluded"]["observationRows"] == overlap["accepted_rds_storable_observations"], "cutover overlap observation drift")
    require(cutover["sourceFailureEvidence"]["http429Records"] == overlap["by_http_status"]["429"], "cutover HTTP 429 drift")
    terminal_summary = summary["terminalSourceFreeze"]
    require(terminal_summary["fullRecordRawObjects"] == terminal_full["objects"], "summary terminal object drift")
    require(terminal_summary["recordObjects"] == terminal_full["record_objects"], "summary terminal record drift")
    require(terminal_summary["rawObjects"] == terminal_full["raw_objects"], "summary terminal raw drift")
    require(terminal_summary["bytes"] == terminal_full["bytes"], "summary terminal byte drift")
    require(terminal_summary["manifestSha256"] == terminal_full["manifest_sha256"], "summary terminal manifest drift")
    require(terminal_summary["activePartitionManifestSha256"] == terminal_partition["manifest_sha256"], "summary partition manifest drift")
    require(instant(terminal_summary["lastModifiedUtc"]) == instant(terminal_full["last_modified_utc"]), "summary terminal LastModified drift")
    require(terminal_summary["recordRawBijectionErrors"] == 0, "summary terminal bijection drift")
    require(terminal_summary["lateBaseObjects"] == 0, "summary terminal late drift")
    require(terminal_summary["awsWritePerformedByAudit"] is False, "summary audit write drift")
    mapping_freeze = mapping["terminalSourceFreeze"]
    require(mapping_freeze["fullRecordRaw"]["manifestSha256"] == TERMINAL_FULL_MANIFEST, "mapping terminal manifest drift")
    require(mapping_freeze["activePartitionManifestSha256"] == TERMINAL_PARTITION_MANIFEST, "mapping partition manifest drift")
    require(mapping_freeze["bijectionErrors"] == 0, "mapping bijection drift")
    require(mapping_freeze["lateBaseObjects"] == 0, "mapping late drift")
    require(
        fixture["terminalSourceFreezeEvidence"]
        == {
            "listReceipts": ["source-freeze-confirmation-1.json", "source-freeze-confirmation-2.json"],
            "byteAuditReceipts": ["continuity-window-final-1.json", "continuity-window-final-2.json"],
            "canonicalContinuity": "continuity-window.json",
        },
        "fixture terminal evidence paths drift",
    )
    trainer_freeze = trainer["snapshot"]["terminalSourceFreeze"]
    require(trainer_freeze["fullRecordRawManifestSha256"] == TERMINAL_FULL_MANIFEST, "trainer terminal manifest drift")
    require(trainer_freeze["trainerImportRows"] == {"observationBatch": 149193, "vehicleObservation": 2461308}, "trainer import count drift")
    require(trainer_freeze["overlapAndQuotaFailureRecordsExcluded"] == 811, "trainer overlap drift")

    measured = sizing["measured"]
    require(
        measured["relationBytes"]["heap"]
        + measured["relationBytes"]["indexes"]
        + measured["relationBytes"]["auxiliary"]
        == measured["relationBytes"]["total"],
        "PostgreSQL relation bytes mismatch",
    )
    require(summary["postgresSizing"]["indexBytes"] == measured["relationBytes"]["indexes"], "sizing summary drift")
    sizing_projection = sizing["projectedThroughTargetAuthorityBoundary"]
    require(
        (
            sizing_projection["sourceRowsToImport"]["observation_batch"],
            sizing_projection["sourceRowsToImport"]["vehicle_observation"],
            sizing_projection["sourceRowsToImport"]["vehicle_id_non_null"],
            sizing_projection["sourceRowsToImport"]["plate_number_non_null"],
        )
        == (149193, 2461308, 2461308, 0),
        "sizing source-row projection drift",
    )
    require(
        (
            sizing_projection["targetRows"]["observation_batchAnchoredMinimum"],
            sizing_projection["targetRows"]["vehicle_observationAnchoredMinimum"],
            sizing_projection["targetRows"]["routeAnchoredMinimum"],
            sizing_projection["targetRows"]["routeVersionAnchoredMinimum"],
            sizing_projection["targetRows"]["routeStopAnchoredMinimum"],
        )
        == (149496, 2470204, 2, 2, 174),
        "sizing target-row projection drift",
    )

    temp_summary = summary["temporaryModelExclusion"]
    require(temp_summary["modelDeploymentId"] == 1, "temp summary id drift")
    require(temp_summary["releaseId"] == TEMP_RELEASE, "temp summary release drift")
    require(temp_summary["bundleDigest"] == TEMP_BUNDLE, "temp summary bundle drift")
    require(temp_summary["calculationVersion"] == TEMP_CALCULATION, "temp summary calculation drift")
    require(temp_summary["activatedAtUtc"] == TEMP_ACTIVATED, "temp summary activation drift")
    require(temp_summary["deletionPerformed"] is False, "unauthorized deletion recorded")
    require(
        trainer["history"]["forecastExclusion"]["temporaryDeployment"]["releaseId"] == TEMP_RELEASE,
        "trainer exclusion drift",
    )
    trainer_carrier = trainer["history"]["forecastExclusion"]["carrierDerivedStatistics"]
    require(trainer_carrier["calculationVersion"] == "observed-max-capacity-v1", "trainer carrier drift")
    require(
        trainer_carrier["temporaryWindow"]["fromInclusiveUtc"] == TEMP_ACTIVATED,
        "trainer carrier window drift",
    )
    dry_run_lines = TEMP_DRY_RUN.read_text(encoding="utf-8").splitlines()
    require(
        not any(line.strip().upper().startswith("DELETE ") for line in dry_run_lines),
        "dry-run script contains DELETE",
    )
    require(
        any("observed-max-capacity-v1" in line for line in dry_run_lines),
        "dry-run script misses carrier generation",
    )
    require(
        any("formal_cutover_at" in line for line in dry_run_lines),
        "dry-run script misses cutover boundary",
    )
    preflight_lines = TARGET_PREFLIGHT.read_text(encoding="utf-8").splitlines()
    require(
        not any(line.strip().upper().startswith(("INSERT ", "UPDATE ", "DELETE ", "ALTER ")) for line in preflight_lines),
        "target preflight contains mutation",
    )
    require(
        any("REPEATABLE READ READ ONLY" in line for line in preflight_lines),
        "target preflight is not read-only",
    )
    require(
        any("'live_route_ranges', live_route_ranges.ranges" in line for line in preflight_lines),
        "target preflight misses live route ranges",
    )
    boundary_lines = BOUNDARY_DRY_RUN.read_text(encoding="utf-8").splitlines()
    boundary_text = "\n".join(boundary_lines)
    require(
        not any(
            line.strip().upper().startswith(("INSERT ", "UPDATE ", "DELETE ", "ALTER "))
            for line in boundary_lines
        ),
        "boundary script contains mutation",
    )
    require("salmonbus-boundary-continuity-readonly-v2" in boundary_text, "boundary schema drift")
    require(ROUTE_AUTHORITIES["3330"] in boundary_text, "3330 boundary authority missing")
    require(ROUTE_AUTHORITIES["1650"] in boundary_text, "1650 boundary authority missing")
    require("2026-09-02T10:27:45.315Z" in boundary_text, "3330 frozen source edge missing")
    require("2026-09-02T12:49:31.467Z" in boundary_text, "1650 frozen source edge missing")
    require("'source_last_response'" in boundary_text, "route source edge output missing")
    require("'target_first_response'" in boundary_text, "route target edge output missing")
    require("'observed_gap_seconds'" in boundary_text, "route gap output missing")
    require("'unexpected_route_version_batches'" in boundary_text, "route-version guard missing")
    require("'shared_vehicle_exact_route_version'" in boundary_text, "exact-version identity output missing")
    require("'expected_gap_seconds'" not in boundary_text, "global expected-gap assertion remains executable")
    require("legacy" not in boundary_text.lower(), "legacy boundary remains executable")
    require("content_digest" not in boundary_text, "boundary must not use a content bridge")
    require(any("vehicle_values_emitted" in line for line in boundary_lines), "boundary privacy assertion missing")
    require(trainer["privacy"]["trainerMayReadRawVehicleIdInsidePrivateRdsTransaction"] is True, "trainer identity drift")
    require(trainer["privacy"]["trainerMayEmitRawVehicleId"] is False, "trainer emission drift")

    require(all_false(cutover["privacy"], cutover["privacy"].keys()), "cutover privacy failure")
    require(
        all_false(
            source["privacy"],
            (
                "object_keys_emitted",
                "original_vehicle_ids_emitted",
                "plate_values_emitted",
                "row_level_material_persisted",
                "secrets_emitted",
                "vehicle_hmacs_emitted",
            ),
        ),
        "source validation privacy failure",
    )
    for path, document in (
        (SOURCE_VALIDATION, source),
        (INVENTORY, inventory),
        (CONTINUITY, continuity),
        (CONTINUITY_FINAL_1, continuity_final_1),
        (CONTINUITY_FINAL_2, continuity_final_2),
        (SOURCE_FREEZE_1, source_freeze_1),
        (SOURCE_FREEZE_2, source_freeze_2),
        (CUTOVER_READINESS, cutover),
        (SUMMARY, summary),
        (MAPPING, mapping),
        (FIXTURE, fixture),
        (TRAINER, trainer),
        (SIZING, sizing),
        (PROVENANCE, provenance),
        (ROUTE_SEED, route_seed),
        (ROUTE_MAPPING, route_mapping),
        (TARGET_DEV_DELTA, target_dev_delta),
    ):
        validate_privacy(path, document)
    verify_sha256sums()
    print(
        json.dumps(
            {
                "archive_fixture_records": len(fixture["archiveRecordsWithRuntimeSensitiveValueOmitted"]),
                "automatic_import_batches": maximum["importRows"]["observation_batch"],
                "automatic_import_observations": maximum["importRows"]["vehicle_observation"],
                "privacy_scan": "passed",
                "status": "passed",
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
