#!/usr/bin/env python3
"""Close the immutable source selection from aggregate-only freeze receipts."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from datetime import datetime
from pathlib import Path
from typing import Any


ROUTES = ("1650", "3330")
COHORTS = ("catchUp", "ambiguous", "overlap")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-audit", type=Path, required=True)
    parser.add_argument("--prior-route-audit", type=Path, required=True)
    parser.add_argument("--observation-one", type=Path, required=True)
    parser.add_argument("--observation-two", type=Path, required=True)
    parser.add_argument("--final-content-audit", type=Path, required=True)
    parser.add_argument("--scheduler-verified-at", required=True)
    parser.add_argument("--scheduler-disabled-at", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def read(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_sha256(value: Any) -> str:
    payload = json.dumps(
        value, ensure_ascii=False, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def signature(observation: dict[str, Any]) -> dict[str, Any]:
    return {
        "combined": observation["combined"],
        "routes": observation["routes"],
    }


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output must not exist")
    base = read(args.base_audit)
    prior = read(args.prior_route_audit)
    first = read(args.observation_one)
    second = read(args.observation_two)
    final = read(args.final_content_audit)
    if signature(first) != signature(second):
        raise RuntimeError("post_disable_inventory_not_stable")
    first_at = datetime.fromisoformat(first["observedAtUtc"].replace("Z", "+00:00"))
    second_at = datetime.fromisoformat(second["observedAtUtc"].replace("Z", "+00:00"))
    if second_at <= first_at:
        raise RuntimeError("invalid_observation_order")
    if final["snapshotAtUtc"] != second["observedAtUtc"]:
        raise RuntimeError("content_audit_not_at_final_inventory_snapshot")

    route_integrity: dict[str, Any] = {}
    route_selection: dict[str, Any] = {}
    for route in ROUTES:
        audit_route = final["routes"][route]
        observed_route = second["routes"][route]
        inventory = audit_route["inventory"]
        if (
            inventory["sha256"] != observed_route["manifestSha256"]
            or inventory["recordDocuments"]
            + inventory["rawDocuments"]
            != observed_route["documents"]
            or inventory["bytes"] != observed_route["bytes"]
        ):
            raise RuntimeError(f"final_inventory_content_mismatch:{route}")
        if audit_route["invalidRecordsByCode"]:
            raise RuntimeError(f"invalid_records_present:{route}")
        if not audit_route["membershipInvariantAcrossBoundaryBracket"]:
            raise RuntimeError(f"ambiguous_boundary_membership:{route}")
        cohorts = audit_route["cohorts"]
        if any(cohorts["ambiguous"][field] for field in (
            "acceptedRecordDocuments", "acceptedRawDocuments", "quarantinedRecords"
        )):
            raise RuntimeError(f"nonempty_ambiguous_cohort:{route}")
        accepted_records = sum(cohorts[name]["acceptedRecordDocuments"] for name in COHORTS)
        accepted_raw = sum(cohorts[name]["acceptedRawDocuments"] for name in COHORTS)
        raw_less = sum(cohorts[name]["acceptedRawLessRecords"] for name in COHORTS)
        quarantined = sum(cohorts[name]["quarantinedRecords"] for name in COHORTS)
        unaccounted_raw = inventory["rawDocuments"] - accepted_raw
        bijection = (
            inventory["recordDocuments"] == accepted_records + quarantined
            and accepted_records == accepted_raw + raw_less
            and unaccounted_raw == quarantined
            and inventory["recordDocuments"] == inventory["rawDocuments"] + raw_less
        )
        if not bijection:
            raise RuntimeError(f"record_raw_bijection_failed:{route}")
        current_catchup = cohorts["catchUp"]
        prior_catchup = prior["routes"][route]["cohorts"]["catchUp"]
        stable_fields = (
            "acceptedManifestSha256",
            "acceptedRecordDocuments",
            "acceptedRawDocuments",
            "acceptedRawLessRecords",
            "acceptedObservations",
            "quarantinedRecords",
        )
        if any(current_catchup[field] != prior_catchup[field] for field in stable_fields):
            raise RuntimeError(f"late_catchup_object_detected:{route}")
        route_integrity[route] = {
            "status": "PASS",
            "recordDocuments": inventory["recordDocuments"],
            "referencedRawDocuments": accepted_raw,
            "validatedQuarantinedRawDocuments": unaccounted_raw,
            "declaredRawLessRecords": raw_less,
            "unreferencedRawDocuments": 0,
            "invalidRecords": 0,
            "recordRawBijection": True,
        }
        route_selection[route] = {
            "boundaryLowerUtc": audit_route["boundaryLowerUtc"],
            "boundaryUpperUtc": audit_route["boundaryUpperUtc"],
            "catchUp": current_catchup,
            "overlap": cohorts["overlap"],
            "lateAcceptedCatchUpObjectsSincePriorAudit": 0,
        }

    components = {
        "baseAcceptedManifestSha256": base["source"]["accepted_manifest_sha256"],
        "baseRangeKst": [base["source"]["from_date"], base["source"]["through_date"]],
        "finalPartitionDateKst": second["partitionDateKst"],
        "finalPartitionFreezeAtUtc": second["observedAtUtc"],
        "finalPartitionInventorySha256": second["combined"]["manifestSha256"],
        "catchUpAcceptedManifestSha256ByRoute": {
            route: route_selection[route]["catchUp"]["acceptedManifestSha256"]
            for route in ROUTES
        },
    }
    coefficient_observations = base["totals"]["accepted_observations"]
    catchup_observations = sum(
        route_selection[route]["catchUp"]["acceptedObservations"] for route in ROUTES
    )
    overlap_observations = sum(
        route_selection[route]["overlap"]["acceptedObservations"] for route in ROUTES
    )
    receipt = {
        "schemaVersion": "v4-1-final-source-closure-v1",
        "classification": "FINAL_FREEZE_CLOSED",
        "scheduler": {
            "accountId": "827325854159",
            "group": "salmonbus-collector",
            "name": "salmonbus-adaptive-heartbeat",
            "state": "DISABLED",
            "disabledAt": args.scheduler_disabled_at,
            "readOnlyVerifiedAt": args.scheduler_verified_at,
            "lambdaAndS3Preserved": True,
        },
        "stability": {
            "observationOneUtc": first["observedAtUtc"],
            "observationTwoUtc": second["observedAtUtc"],
            "separationSeconds": int((second_at - first_at).total_seconds()),
            "objectDelta": 0,
            "byteDelta": 0,
            "inventoryChanged": False,
            "lastModifiedMaxUtcByRoute": {
                route: max(
                    value["lastModifiedMaxUtc"]
                    for value in second["routes"][route]["kinds"].values()
                    if value["lastModifiedMaxUtc"] is not None
                )
                for route in ROUTES
            },
        },
        "inventory": {
            "partitionDateKst": second["partitionDateKst"],
            "freezeAtUtc": second["observedAtUtc"],
            **second["combined"],
            "algorithm": "object-inventory-v1:key<TAB>etag<TAB>size<LF>, bytewise key order",
        },
        "integrity": {
            "status": "PASS",
            "routes": route_integrity,
            "lateAcceptedCatchUpObjects": 0,
            "ambiguousBoundaryRecords": 0,
        },
        "selection": {
            "coefficientTraining": {
                "rangeKst": components["baseRangeKst"],
                "acceptedObservations": coefficient_observations,
                "acceptedManifestSha256": components["baseAcceptedManifestSha256"],
            },
            "routeCatchUpForSeedAndMigration": {
                "acceptedObservations": catchup_observations,
                "routes": route_selection,
            },
            "sourceAuthorityObservations": coefficient_observations + catchup_observations,
            "rdsOverlap": {
                "acceptedObservations": overlap_observations,
                "usage": "continuity/dedupe verification only; excluded from migration insert and coefficient training",
            },
        },
        "components": components,
        "sourceClosureSha256": canonical_sha256(components),
        "receipts": {
            "baseAudit": {
                "path": str(args.base_audit),
                "sha256": file_sha256(args.base_audit),
            },
            "priorRouteAudit": {
                "path": str(args.prior_route_audit),
                "sha256": file_sha256(args.prior_route_audit),
            },
            "observationOne": {
                "path": str(args.observation_one),
                "sha256": file_sha256(args.observation_one),
            },
            "observationTwo": {
                "path": str(args.observation_two),
                "sha256": file_sha256(args.observation_two),
            },
            "finalContentAudit": {
                "path": str(args.final_content_audit),
                "sha256": file_sha256(args.final_content_audit),
            },
        },
        "privacy": {
            "aggregateOnly": True,
            "objectKeysEmitted": False,
            "rowLevelMaterialPersisted": False,
            "vehicleIdsEmitted": False,
            "vehicleHmacsEmitted": False,
            "plateValuesEmitted": False,
            "secretsEmitted": False,
        },
        "remoteWritesPerformed": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n")
    os.replace(temporary, args.output)
    print(
        json.dumps(
            {
                "classification": receipt["classification"],
                "inventorySha256": receipt["inventory"]["manifestSha256"],
                "sourceClosureSha256": receipt["sourceClosureSha256"],
                "sourceAuthorityObservations": receipt["selection"]["sourceAuthorityObservations"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
