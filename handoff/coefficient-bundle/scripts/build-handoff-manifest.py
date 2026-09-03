#!/usr/bin/env python3
"""Build the handoff manifest after all other package files are final."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


BUNDLE_DIRECTORY = "bundle/NON_COMPLIANT_WITH_V4_1_production-a18-a748cba6ca735e36"


def sha256_file(filename: Path) -> str:
    digest = hashlib.sha256()
    with filename.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def role_of(relative: str) -> str:
    if relative.startswith("bundle/"):
        return "actual_production_bundle"
    if relative.startswith("raw/"):
        return "safe_source_provenance"
    if relative.startswith("processed/"):
        return "derived_validation_or_inventory"
    if relative.startswith("scripts/"):
        return "reproduction_script"
    if relative.endswith(".md"):
        return "handoff_documentation"
    return "supporting_file"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    root = args.root.resolve()

    bundle_manifest = json.loads((root / BUNDLE_DIRECTORY / "manifest.json").read_text())
    source_inventory = json.loads((root / "processed/source-inventory.json").read_text())
    files: list[dict[str, Any]] = []
    excluded_prefixes = (".repro-venv/", ".uv-cache/", ".uv-python/", "processed/rebuild-work/")

    for filename in sorted(root.rglob("*")):
        if not filename.is_file() or filename.is_symlink():
            continue
        relative = filename.relative_to(root).as_posix()
        if relative == "manifest.json" or relative.endswith(".build-receipt.json") and relative.startswith(
            "processed/rebuild-work"
        ):
            continue
        if any(relative.startswith(prefix) for prefix in excluded_prefixes):
            continue
        entry: dict[str, Any] = {
            "path": relative,
            "bytes": filename.stat().st_size,
            "sha256": sha256_file(filename),
            "role": role_of(relative),
        }
        if relative in {
            "raw/aggregate-build-receipt.json",
            f"{BUNDLE_DIRECTORY}/manifest.json",
            f"{BUNDLE_DIRECTORY}/weights.safetensors",
        }:
            entry["row_count"] = 1_236_608
            entry["data_period_kst"] = {"from": "2026-08-14", "through": "2026-08-23"}
        files.append(entry)

    output = {
        "schema_version": "coefficient-bundle-handoff-v1",
        "classification": "NON_COMPLIANT_WITH_V4_1",
        "generated_at_utc": datetime.now(timezone.utc).isoformat(),
        "manifest_self": {
            "path": "manifest.json",
            "sha256": None,
            "note": "self is intentionally excluded from the file digests"
        },
        "authority": {
            "dev_v4_1_commit": "b239691512dc5c498ae7013cd786e0b627f0c010",
            "producer_commit": "c5ff99da81ed0af9916bd5aa5115d89f49258e2c",
            "site_support_commit": "a0924128467e59b54efa00421b8d6a1db5f6d5f0"
        },
        "bundle": {
            "directory": BUNDLE_DIRECTORY,
            "schema_version": bundle_manifest["schema_version"],
            "model_version": bundle_manifest["model_version"],
            "release_id": bundle_manifest["release_id"],
            "bundle_sha256": "652ee361876e2ab38993472ea65fcd182f2737bc7d819c4bfdb8c8c3850f9335",
            "data_through": bundle_manifest["data_through"],
            "routes": bundle_manifest["routes"],
            "horizons": bundle_manifest["horizons"],
            "feature_count": bundle_manifest["features"]["count"],
            "tensor_count": len(bundle_manifest["weights"]["tensors"]),
            "fit_row_count": sum(
                int(count)
                for route in bundle_manifest["training"]["fit_rows"].values()
                for count in route.values()
            ),
            "dev_drop_in_compatible": False
        },
        "source": {
            "training_period_kst": {"from": "2026-08-14", "through": "2026-08-23"},
            "record_documents": 65_152,
            "raw_documents": 65_095,
            "normalized_observations": 992_866,
            "point_events": 114_945,
            "finalized_examples": 1_236_608,
            "combined_manifest_sha256": (
                "3e1628f0240515db38c73f04dac6346596a28a663bac7d55e0ef84af25e15536"
            ),
            "row_level_source_included": False,
            "row_level_source_exclusion_reason": "private vehicle, plate, and HMAC fields"
        },
        "live_inventory_observation": {
            "observed_at_utc": source_inventory["_inventory"]["observed_at_utc"],
            "date_min": source_inventory["records"]["date_min"],
            "date_max": source_inventory["records"]["date_max"],
            "record_objects": source_inventory["records"]["objects"],
            "record_bytes": source_inventory["records"]["bytes"],
            "raw_objects": source_inventory["raw"]["objects"],
            "raw_bytes": source_inventory["raw"]["bytes"]
        },
        "file_count_excluding_self": len(files),
        "files": files
    }
    print(json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
