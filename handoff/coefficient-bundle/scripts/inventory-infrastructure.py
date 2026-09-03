#!/usr/bin/env python3
"""Read-only, value-whitelisted infrastructure inventory for this coefficient handoff."""

from __future__ import annotations

import argparse
import collections
import json
from datetime import datetime, timezone
from typing import Any

import boto3


COLLECTOR_BUCKET = "salmonbus-collector-827325854159-apne2"
SERVING_BUCKET = "salmonbus-shadow-serving-827325854159-ap-northeast-2"
CURRENT_POINTER = "live/control/tailers/current.json"
EVALUATION_POINTER = "derived/model-evaluation/private/evaluation-latest.json"
RELEASE_ARTIFACT = (
    "artifacts/backend/c5ff99da81ed0af9916bd5aa5115d89f49258e2c/"
    "0226182ff73a0cb82d735268afd92e019137acf677ce5b9f08f0bc6950dba8a0.tar.gz"
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", default="salmonbus-admin")
    parser.add_argument("--region", default="ap-northeast-2")
    return parser.parse_args()


def iso(value: Any) -> Any:
    return value.isoformat() if hasattr(value, "isoformat") else value


def identity_values(document: Any) -> dict[str, list[Any]]:
    wanted = {"releaseId", "bundleDigest", "modelVersion", "dataThrough"}
    result: collections.defaultdict[str, set[Any]] = collections.defaultdict(set)

    def walk(value: Any) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key in wanted and isinstance(child, (str, int, float, bool, type(None))):
                    result[key].add(child)
                walk(child)
        elif isinstance(value, list):
            for child in value:
                walk(child)

    walk(document)
    return {key: sorted(values, key=str) for key, values in sorted(result.items())}


def main() -> int:
    args = arguments()
    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    lambdas = session.client("lambda")
    scheduler = session.client("scheduler")
    ec2 = session.client("ec2")
    ssm = session.client("ssm")
    logs = session.client("logs")
    s3 = session.client("s3")

    function_names = ("salmonbus-collector", "salmonbus-model-evaluator", "salmonbus-live")
    functions = []
    for name in function_names:
        value = lambdas.get_function_configuration(FunctionName=name)
        functions.append(
            {
                "name": name,
                "runtime": value.get("Runtime"),
                "architectures": value.get("Architectures"),
                "last_modified": value.get("LastModified"),
                "state": value.get("State"),
                "last_update_status": value.get("LastUpdateStatus"),
                "memory_mb": value.get("MemorySize"),
                "timeout_seconds": value.get("Timeout"),
                "code_size": value.get("CodeSize"),
                "code_sha256_base64": value.get("CodeSha256"),
                "environment_keys": sorted(value.get("Environment", {}).get("Variables", {})),
                "environment_values_emitted": False,
            }
        )

    schedules = []
    for group, name in (
        ("salmonbus-collector", "salmonbus-adaptive-heartbeat"),
        ("default", "salmonbus-model-evaluation-daily"),
    ):
        value = scheduler.get_schedule(GroupName=group, Name=name)
        schedules.append(
            {
                "group": group,
                "name": name,
                "state": value.get("State"),
                "expression": value.get("ScheduleExpression"),
                "timezone": value.get("ScheduleExpressionTimezone"),
                "target_arn": value.get("Target", {}).get("Arn"),
                "retry_policy": value.get("Target", {}).get("RetryPolicy"),
                "last_modified": iso(value.get("LastModificationDate")),
            }
        )

    instance_id = "i-06dbf1dffbdb7fb24"
    instance = ec2.describe_instances(InstanceIds=[instance_id])["Reservations"][0]["Instances"][0]
    managed = ssm.describe_instance_information(
        Filters=[{"Key": "InstanceIds", "Values": [instance_id]}]
    )["InstanceInformationList"][0]

    current = json.loads(
        s3.get_object(Bucket=SERVING_BUCKET, Key=CURRENT_POINTER)["Body"].read()
    )
    snapshot_key = current["snapshotKey"]
    snapshot_head = s3.head_object(Bucket=SERVING_BUCKET, Key=snapshot_key)
    snapshot = json.loads(
        s3.get_object(Bucket=SERVING_BUCKET, Key=snapshot_key)["Body"].read()
    )

    evaluation_head = s3.head_object(Bucket=COLLECTOR_BUCKET, Key=EVALUATION_POINTER)
    evaluation = json.loads(
        s3.get_object(Bucket=COLLECTOR_BUCKET, Key=EVALUATION_POINTER)["Body"].read()
    )
    artifact = s3.head_object(Bucket=SERVING_BUCKET, Key=RELEASE_ARTIFACT)

    latest_logs: dict[str, Any] = {}
    for name in function_names:
        streams = logs.describe_log_streams(
            logGroupName=f"/aws/lambda/{name}",
            orderBy="LastEventTime",
            descending=True,
            limit=1,
        ).get("logStreams", [])
        latest_logs[name] = (
            None
            if not streams
            else datetime.fromtimestamp(
                streams[0]["lastEventTimestamp"] / 1000, timezone.utc
            ).isoformat()
        )

    evaluator_failures = []
    known_failures = {
        "date_partition_mismatch": "ValidationError:date_partition_mismatch",
        "horizon refresh requires an exact completed corpus": (
            "RuntimeError:horizon_refresh_requires_exact_completed_corpus"
        ),
    }
    evaluator_streams = logs.describe_log_streams(
        logGroupName="/aws/lambda/salmonbus-model-evaluator",
        orderBy="LastEventTime",
        descending=True,
        limit=6,
    ).get("logStreams", [])
    for stream in evaluator_streams:
        found = set()
        for event in logs.get_log_events(
            logGroupName="/aws/lambda/salmonbus-model-evaluator",
            logStreamName=stream["logStreamName"],
            startFromHead=True,
            limit=10000,
        ).get("events", []):
            message = event.get("message", "")
            for needle, safe_name in known_failures.items():
                if needle in message:
                    found.add(safe_name)
        evaluator_failures.append(
            {
                "last_event_utc": datetime.fromtimestamp(
                    stream["lastEventTimestamp"] / 1000, timezone.utc
                ).isoformat(),
                "fixed_failure_codes": sorted(found),
                "raw_log_message_emitted": False,
            }
        )

    output = {
        "observed_at_utc": datetime.now(timezone.utc).isoformat(),
        "aws_profile_name": args.profile,
        "region": args.region,
        "read_only_operations": True,
        "lambdas": functions,
        "schedules": schedules,
        "latest_lambda_log_event_utc": latest_logs,
        "recent_evaluator_failures": evaluator_failures,
        "demo_ec2": {
            "instance_id": instance_id,
            "state": instance.get("State", {}).get("Name"),
            "instance_type": instance.get("InstanceType"),
            "launch_time": iso(instance.get("LaunchTime")),
            "name": next(
                (tag["Value"] for tag in instance.get("Tags", []) if tag["Key"] == "Name"),
                None,
            ),
            "ssm_ping_status": managed.get("PingStatus"),
            "ssm_last_ping": iso(managed.get("LastPingDateTime")),
            "platform": managed.get("PlatformName"),
            "platform_version": managed.get("PlatformVersion"),
        },
        "live_snapshot": {
            "snapshot_id": current.get("snapshotId"),
            "generated_at": snapshot.get("generatedAt"),
            "last_modified": iso(snapshot_head.get("LastModified")),
            "bytes": snapshot_head.get("ContentLength"),
            "model_identity_values": identity_values(snapshot),
            "vehicle_or_plate_values_emitted": False,
            "snapshot_object_key_emitted": False,
        },
        "latest_evaluation": {
            "last_modified": iso(evaluation_head.get("LastModified")),
            "bytes": evaluation_head.get("ContentLength"),
            "schema_version": evaluation.get("schema_version"),
            "evaluation_id": evaluation.get("evaluation_id"),
            "generated_at": evaluation.get("generated_at"),
            "observed_from": evaluation.get("observed_from"),
            "observed_through": evaluation.get("observed_through"),
            "protocol_version": evaluation.get("protocol_version"),
            "sample": evaluation.get("sample"),
        },
        "release_artifact": {
            "bytes": artifact.get("ContentLength"),
            "last_modified": iso(artifact.get("LastModified")),
            "version_id": artifact.get("VersionId"),
            "metadata": artifact.get("Metadata"),
            "object_key_emitted": False,
        },
    }
    print(json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
