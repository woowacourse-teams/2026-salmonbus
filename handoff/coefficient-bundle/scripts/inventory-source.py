#!/usr/bin/env python3
"""Read-only aggregate inventory for collector S3 data without emitting object keys or values."""

from __future__ import annotations

import argparse
import collections
import json
import re
from datetime import datetime, timezone
from typing import Any

import boto3


DATE_PATTERN = re.compile(r"dt=(\d{4}-\d{2}-\d{2})")
ROUTE_PATTERN = re.compile(r"route=([^/]+)")
SENSITIVE_FIELD = re.compile(
    r"plate|veh|hmac|token|secret|service.?key|api.?key", re.IGNORECASE
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--profile", default="salmonbus-admin")
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--sample-date", required=True)
    return parser.parse_args()


def shape_of(value: Any, depth: int = 0) -> Any:
    if depth > 6:
        return type(value).__name__
    if isinstance(value, dict):
        return {key: shape_of(child, depth + 1) for key, child in sorted(value.items())}
    if isinstance(value, list):
        return {
            "type": "array",
            "length": len(value),
            "item": shape_of(value[0], depth + 1) if value else None,
        }
    if isinstance(value, str):
        return "string"
    return type(value).__name__


def sensitive_paths(value: Any, current: str = "$") -> list[str]:
    result: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            child_path = f"{current}.{key}"
            if SENSITIVE_FIELD.search(key):
                result.append(child_path)
            result.extend(sensitive_paths(child, child_path))
    elif isinstance(value, list) and value:
        result.extend(sensitive_paths(value[0], current + "[]"))
    return result


def main() -> int:
    args = arguments()
    session = boto3.Session(profile_name=args.profile, region_name=args.region)
    s3 = session.client("s3")
    output: dict[str, Any] = {
        "_inventory": {
            "observed_at_utc": datetime.now(timezone.utc).isoformat(),
            "bucket": args.bucket,
            "region": args.region,
            "aws_operations": ["s3:ListBucket", "s3:GetObject"],
            "object_keys_emitted": False,
            "field_values_emitted": False,
        }
    }

    for family in ("records", "raw"):
        count = 0
        byte_count = 0
        oldest = None
        newest = None
        dates: collections.Counter[str] = collections.Counter()
        date_bytes: collections.Counter[str] = collections.Counter()
        route_dates: collections.Counter[tuple[str, str]] = collections.Counter()
        sample_objects: list[dict[str, Any]] = []

        paginator = s3.get_paginator("list_objects_v2")
        for page in paginator.paginate(
            Bucket=args.bucket,
            Prefix=f"{family}/",
            PaginationConfig={"PageSize": 1000},
        ):
            for item in page.get("Contents", []):
                key = item["Key"]
                modified = item["LastModified"]
                size = int(item["Size"])
                count += 1
                byte_count += size
                oldest = modified if oldest is None or modified < oldest else oldest
                newest = modified if newest is None or modified > newest else newest
                date_match = DATE_PATTERN.search(key)
                route_match = ROUTE_PATTERN.search(key)
                if date_match:
                    day = date_match.group(1)
                    dates[day] += 1
                    date_bytes[day] += size
                    if route_match:
                        route_dates[(day, route_match.group(1))] += 1
                    if day == args.sample_date:
                        sample_objects.append(item)

        if not sample_objects:
            raise SystemExit(f"no {family} objects for sample date")
        latest_sample = max(sample_objects, key=lambda item: item["LastModified"])
        body = s3.get_object(Bucket=args.bucket, Key=latest_sample["Key"])["Body"].read()
        document = json.loads(body)

        output[family] = {
            "objects": count,
            "bytes": byte_count,
            "date_min": min(dates),
            "date_max": max(dates),
            "oldest_last_modified": oldest.isoformat(),
            "newest_last_modified": newest.isoformat(),
            "latest_10_dates": [
                {
                    "date": day,
                    "objects": dates[day],
                    "bytes": date_bytes[day],
                    "by_route": {
                        route: route_dates[(day, route)] for route in ("1650", "3330")
                    },
                }
                for day in sorted(dates)[-10:]
            ],
            "sample": {
                "date": args.sample_date,
                "bytes": len(body),
                "last_modified": latest_sample["LastModified"].isoformat(),
                "schema": shape_of(document),
                "sensitive_field_paths": sorted(set(sensitive_paths(document))),
                "object_key_emitted": False,
                "values_emitted": False,
            },
        }

    print(json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
