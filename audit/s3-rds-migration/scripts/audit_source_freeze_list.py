#!/usr/bin/env python3
"""List-only terminal source freeze receipt without emitting object keys."""

from __future__ import annotations

import argparse
import collections
import json
from datetime import date, datetime, timezone
from pathlib import Path
from typing import Any

import boto3

import audit_inventory as base


UTC = timezone.utc
SOURCE_ACCOUNT = "827325854159"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--profile", default="default")
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--base-through-date", required=True)
    parser.add_argument("--terminal-partition-date", required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def iso(value: datetime | None) -> str | None:
    if value is None:
        return None
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def summary(objects: list[base.ObjectInfo]) -> dict[str, Any]:
    records = [item for item in objects if base.family_of(item.key) == "records"]
    raw = [item for item in objects if base.family_of(item.key) == "raw"]
    return {
        "objects": len(objects),
        "record_objects": len(records),
        "raw_objects": len(raw),
        "bytes": sum(item.size for item in objects),
        "manifest_sha256": base.inventory_digest(objects),
        "record_last_modified_utc": iso(max((item.last_modified for item in records), default=None)),
        "raw_last_modified_utc": iso(max((item.last_modified for item in raw), default=None)),
        "last_modified_utc": iso(max((item.last_modified for item in objects), default=None)),
    }


def main() -> int:
    args = arguments()
    if args.output.exists() or args.output.is_symlink():
        raise SystemExit("output already exists")
    base_through = date.fromisoformat(args.base_through_date)
    terminal_day = date.fromisoformat(args.terminal_partition_date)
    if terminal_day <= base_through:
        raise SystemExit("terminal partition must follow immutable base")

    client = boto3.Session(profile_name=args.profile, region_name=args.region).client("s3")
    observed_at = datetime.now(UTC)
    all_objects = base.list_objects(client, args.bucket)
    source_objects: list[base.ObjectInfo] = []
    immutable_base: list[base.ObjectInfo] = []
    terminal_partition: list[base.ObjectInfo] = []
    invalid_paths = collections.Counter()
    late_base = collections.Counter()

    for info in all_objects:
        family = base.family_of(info.key)
        if family not in {"records", "raw"}:
            continue
        match = base.RECORD_PATH.fullmatch(info.key) if family == "records" else base.RAW_PATH.fullmatch(info.key)
        if match is None:
            invalid_paths[family] += 1
            continue
        object_day = date.fromisoformat(match.group("date"))
        if object_day <= terminal_day:
            source_objects.append(info)
        if object_day <= base_through:
            immutable_base.append(info)
            if info.last_modified > base.freeze_at(object_day.isoformat()):
                late_base[family] += 1
        elif object_day == terminal_day:
            terminal_partition.append(info)

    receipt = {
        "schema_version": "salmonbus-terminal-source-freeze-list-v1",
        "source_account": SOURCE_ACCOUNT,
        "base_through_kst_date_inclusive": base_through.isoformat(),
        "terminal_partition_kst_date": terminal_day.isoformat(),
        "observed_at_utc": iso(observed_at),
        "aws_operations": ["s3:ListBucket"],
        "immutable_base": summary(immutable_base),
        "terminal_partition": summary(terminal_partition),
        "terminal_source_through_partition": summary(source_objects),
        "late_base_objects_after_next_day_00_15_kst": dict(sorted(late_base.items())),
        "invalid_partition_paths": dict(sorted(invalid_paths.items())),
        "privacy": {
            "object_keys_emitted": False,
            "row_level_data_emitted": False,
            "vehicle_or_plate_values_emitted": False,
            "hmac_values_emitted": False,
            "credentials_or_environment_values_emitted": False,
        },
        "mutation_performed": False,
    }
    args.output.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"objects": len(source_objects), "status": "succeeded"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:
        raise SystemExit("source freeze list failed without emitting object identifiers") from None
