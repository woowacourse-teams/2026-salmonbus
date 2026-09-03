#!/usr/bin/env python3
"""Negative test for TEMPORARY_SMOKE_ONLY lineage contamination."""

from __future__ import annotations

import argparse
import gzip
import json
import os
from datetime import datetime, timezone
from pathlib import Path


TEMP = {
    "deploymentId": 1,
    "releaseId": "salmonbus-d57370be9195520e",
    "bundleDigest": "d57370be9195520ecf3b0ef125aa3611090ed5f41ade2963c33f38d99a29e89a",
    "calculationVersion": "seat-feature-contract-v4-1-2026-09-02",
}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def is_temp_deployment(value: dict[str, object]) -> bool:
    return (
        value["id"] == TEMP["deploymentId"]
        or value["releaseId"] == TEMP["releaseId"]
        or value["bundleDigest"] == TEMP["bundleDigest"]
        or value["calculationVersion"] == TEMP["calculationVersion"]
    )


def main() -> int:
    args = arguments()
    policy = json.loads((args.root / "processed" / "input-exclusion-policy.json").read_text())
    manifest = json.loads((args.root / "bundle" / "manifest.json").read_text())
    seed = json.loads(
        gzip.decompress(args.root.joinpath("seed/cell-hourly-aggregate.json.gz").read_bytes())
    )
    source = json.loads((args.root / "processed" / "source-audit.json").read_text())
    watermark = json.loads(
        (args.root / "processed" / "label-watermark-audit.json").read_text()
    )

    observations = [
        {"id": 10, "batch": 100},
        {"id": 11, "batch": 101},
    ]
    deployments = [
        {
            "id": 1,
            "releaseId": TEMP["releaseId"],
            "bundleDigest": TEMP["bundleDigest"],
            "calculationVersion": TEMP["calculationVersion"],
        },
        {
            "id": 2,
            "releaseId": manifest["releaseId"],
            "bundleDigest": manifest["weightsDigest"],
            "calculationVersion": manifest["featureContractVersion"],
        },
    ]
    forecast = [
        {"id": 20, "deploymentId": 1, "observationId": 10},
        {"id": 21, "deploymentId": 2, "observationId": 11},
    ]
    statistics = [
        {"id": 30, "route": 1, "calculationVersion": TEMP["calculationVersion"], "revision": 1,
         "dataUntil": "2026-09-02T12:00:00Z", "computedAt": "2026-09-02T12:00:00Z"},
        {"id": 31, "route": 1, "calculationVersion": manifest["featureContractVersion"], "revision": 2,
         "dataUntil": "2026-09-02T12:05:00Z", "computedAt": "2026-09-02T12:05:00Z"},
        {"id": 32, "route": 1, "calculationVersion": manifest["featureContractVersion"], "revision": 0,
         "dataUntil": "2026-09-02T11:00:00Z", "computedAt": "2026-09-02T11:00:00Z"},
        {"id": 33, "route": 1, "calculationVersion": manifest["featureContractVersion"], "revision": 3,
         "dataUntil": "2026-09-02T13:00:00Z", "computedAt": "2026-09-02T13:00:00Z"},
        {"id": 34, "route": 1, "calculationVersion": "unrelated-version", "revision": 1,
         "dataUntil": "2026-09-02T12:10:00Z", "computedAt": "2026-09-02T12:10:00Z"},
    ]
    deployment_by_id = {item["id"]: item for item in deployments}
    eligible_forecast = [
        item
        for item in forecast
        if not is_temp_deployment(deployment_by_id[item["deploymentId"]])
    ]
    activation = datetime.fromisoformat("2026-09-02T11:55:04.729493+00:00")
    cutover = datetime.fromisoformat("2026-09-02T13:00:00+00:00")
    candidate_versions = {
        TEMP["calculationVersion"], manifest["featureContractVersion"]
    }
    locked_generations = {
        (
            item["route"], item["calculationVersion"], item["revision"],
            item["dataUntil"], item["computedAt"],
        )
        for item in statistics
        if activation
        <= datetime.fromisoformat(str(item["computedAt"]).replace("Z", "+00:00"))
        < cutover
        and item["calculationVersion"] in candidate_versions
    }
    eligible_statistics = [
        item
        for item in statistics
        if (
            item["route"], item["calculationVersion"], item["revision"],
            item["dataUntil"], item["computedAt"],
        ) not in locked_generations
    ]
    checks = {
        "exactPolicyIdentifiers": policy["excludedDeployment"]["deploymentId"] == TEMP["deploymentId"]
        and policy["excludedDeployment"]["releaseId"] == TEMP["releaseId"]
        and policy["excludedDeployment"]["bundleDigest"] == TEMP["bundleDigest"]
        and policy["excludedDeployment"]["calculationVersion"] == TEMP["calculationVersion"],
        "temporaryForecastRejected": [item["id"] for item in eligible_forecast] == [21],
        "temporaryStatisticsRejectedByExactGenerationTuple": [item["id"] for item in eligible_statistics]
        == [32, 33, 34],
        "sharedFormalCalculationVersionContaminationRejected": 31
        not in [item["id"] for item in eligible_statistics],
        "calculationVersionOnlyDeletionProhibited": 32
        in [item["id"] for item in eligible_statistics]
        and 33 in [item["id"] for item in eligible_statistics]
        and policy["contaminatedStatisticsPolicy"]["prohibitCalculationVersionOnlyDeletion"] is True,
        "activationBaselineWasEmpty": policy["contaminatedStatisticsPolicy"]["baselineRowsAtActivation"] == 0,
        "observationsRetained": [item["id"] for item in observations] == [10, 11],
        "deploymentLineageRetained": [item["id"] for item in deployments] == [1, 2]
        and policy["excludedDeployment"]["deleteDeploymentRow"] is False
        and policy["excludedDeployment"]["requiredPostReplacementState"] == "RETIRED",
        "forecastCompletedAtResetForbidden": policy["observationPolicy"]["resetForecastCompletedAtToNull"] is False,
        "formalFeatureContractDiffersFromTemp": manifest["featureContractVersion"]
        != TEMP["calculationVersion"],
        "formalFeatureContractMatchesCurrentWriter": manifest["featureContractVersion"]
        == "observed-max-capacity-v1"
        and policy["formalContract"]["currentWriterCalculationVersion"]
        == manifest["featureContractVersion"]
        and policy["formalContract"]["versionsMatch"] is True,
        "seedContractMatchesFormalManifest": seed["featureContractVersion"]
        == manifest["featureContractVersion"],
        "seedPolicyDiffersFromTemp": seed["backfillPolicyId"]
        != TEMP["calculationVersion"],
        "sourceSeedRequiresObservationOnlyRdsDelta": seed[
            "requiresRdsObservationDeltaBeforeFormalCutover"
        ] is True
        and policy["atomicCutover"]["rdsObservationDeltaRequired"] is True,
        "s3ClosurePredatesTempActivation": datetime.fromisoformat(
            watermark["watermarkAtUtc"].replace("Z", "+00:00")
        ) < activation,
        "seedIsIndependentOfLiveStatistics": policy["contaminatedStatisticsPolicy"]["useLiveRdsStatisticsAsSeedInput"] is False,
        "formalForecastBlockedUntilCleanSeedGeneration": policy["atomicCutover"]["formalForecastMayStartBeforeCleanupAndSeed"] is False,
        "trainerDoesNotReadAcademyRds": policy["enforcement"]["academyRdsReadAsTrainingInput"] is False,
        "noRemoteMutation": policy["remoteWriteOrDeploymentPerformedByThisWork"] is False,
    }
    if not all(bool(value) for value in checks.values()):
        raise ValueError("temporary_exclusion_negative_test_failed")
    receipt = {
        "schemaVersion": "temporary-smoke-exclusion-negative-test-v1",
        "status": "PASS",
        "checks": checks,
        "syntheticFixture": {
            "inputForecastRows": len(forecast),
            "eligibleForecastRows": len(eligible_forecast),
            "inputStatisticsRows": len(statistics),
            "eligibleStatisticsRows": len(eligible_statistics),
            "lockedContaminatedGenerations": len(locked_generations),
            "inputObservationRows": len(observations),
            "retainedObservationRows": len(observations),
        },
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
    os.replace(temporary, args.output)
    print(json.dumps({"status": "PASS", "test": "temporary-smoke-exclusion"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
