#!/usr/bin/env python3
"""Validate the production A18 bundle and report its incompatibility with dev v4-1."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from pathlib import Path
from typing import Any

import numpy as np
from safetensors.numpy import load_file


EXPECTED_RELEASE_ID = "a18-a748cba6ca735e36"
EXPECTED_MANIFEST_SHA256 = "2dce5eb26299ebe2ccbe02f02cdc97a7aa9b07ab319690f383b0928fd8b40405"
EXPECTED_WEIGHTS_SHA256 = "680ef1823971d4a7ba102fd3b04259cc679b676502a0e6e425115fb33fd97be4"
EXPECTED_BUNDLE_SHA256 = "652ee361876e2ab38993472ea65fcd182f2737bc7d819c4bfdb8c8c3850f9335"

PRODUCTION_TENSORS = {
    "hurdle_coefficients": ((2, 12, 31), "float64"),
    "anchor_coefficients": ((2, 12, 2), "float64"),
    "sign_coefficients": ((2, 12, 2, 31), "float64"),
    "bin_coefficients": ((2, 12, 2, 9, 31), "float64"),
    "bin_fitted": ((2, 12, 2, 9), "uint8"),
    "lead_seconds": ((2, 12), "float64"),
    "band_seen": ((2, 12, 3), "uint8"),
    "cell_values": ((2, 12, 3, 89, 2), "float64"),
    "cell_seen": ((2, 12, 3, 89), "uint8"),
}

DEV_MANIFEST_FIELDS = {
    "bundleSchemaVersion",
    "modelVersion",
    "releaseId",
    "featureContractVersion",
    "sourceCommit",
    "routeReference",
    "routes",
    "horizonStops",
    "featureNames",
    "normalizationConstants",
    "timeSlotSource",
    "capacityPolicy",
    "cellStatisticsPolicy",
    "tensors",
    "weightsDigest",
    "identityDigest",
    "goldenVectorDigest",
    "goldenVector",
    "dataThrough",
}

DEV_TENSORS = {
    "hurdle_coefficients",
    "anchor_coefficients",
    "sign_coefficients",
    "bin_coefficients",
    "bin_fitted",
}

DEV_FEATURE_NAMES = (
    "constant",
    "is_morning",
    "is_evening",
    "new_time_slot",
    "seats_left_ratio",
    "is_full",
    "low_seat_band",
    "crowd_level_1",
    "crowd_level_2",
    "crowd_level_3",
    "crowd_level_4",
    "maximum_seats_ratio",
    "seat_slope",
    "seat_slope_missing",
    "full_seat_streak",
    "preceding_vehicle_is_full",
    "preceding_vehicle_seats_ratio",
    "preceding_vehicle_missing",
    "route",
    "stop_position_on_route",
    "stop_position_basis_0",
    "stop_position_basis_1",
    "stop_position_basis_2",
    "stop_position_basis_3",
    "stop_position_basis_4",
    "stop_position_basis_5",
    "stop_position_basis_6",
    "stop_position_basis_7",
    "fill_rate_score",
    "net_boarding_segment_score",
    "filled_by_neighbours",
)


def sha256_file(filename: Path) -> str:
    digest = hashlib.sha256()
    with filename.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bundle_digest(manifest: Path, weights: Path) -> str:
    digest = hashlib.sha256()
    for filename in (manifest, weights):
        with filename.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
    return digest.hexdigest()


def coefficient_stats(tensors: dict[str, np.ndarray]) -> dict[str, Any]:
    output: dict[str, Any] = {}
    for name, values in sorted(tensors.items()):
        numeric = np.asarray(values)
        summary: dict[str, Any] = {
            "shape": list(numeric.shape),
            "dtype": str(numeric.dtype),
            "values": int(numeric.size),
        }
        if np.issubdtype(numeric.dtype, np.floating):
            summary.update(
                finite=bool(np.all(np.isfinite(numeric))),
                minimum=float(np.min(numeric)),
                maximum=float(np.max(numeric)),
                maximum_absolute=float(np.max(np.abs(numeric))),
                mean=float(np.mean(numeric)),
                zero_values=int(np.count_nonzero(numeric == 0)),
            )
        else:
            unique, counts = np.unique(numeric, return_counts=True)
            summary["counts"] = {str(int(key)): int(value) for key, value in zip(unique, counts)}
        output[name] = summary
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bundle", type=Path)
    args = parser.parse_args()

    manifest_file = args.bundle / "manifest.json"
    weights_file = args.bundle / "weights.safetensors"
    manifest_bytes = manifest_file.read_bytes()
    manifest = json.loads(manifest_bytes.decode("utf-8"))
    tensors = load_file(str(weights_file))

    canonical = (
        json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n"
    ).encode("utf-8")
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    weights_sha = sha256_file(weights_file)
    exact_bundle_sha = bundle_digest(manifest_file, weights_file)

    errors: list[str] = []
    if manifest_sha != EXPECTED_MANIFEST_SHA256:
        errors.append("manifest_sha256")
    if weights_sha != EXPECTED_WEIGHTS_SHA256:
        errors.append("weights_sha256")
    if exact_bundle_sha != EXPECTED_BUNDLE_SHA256:
        errors.append("bundle_sha256")
    if manifest_bytes != canonical:
        errors.append("manifest_canonical_json")
    if manifest.get("release_id") != EXPECTED_RELEASE_ID:
        errors.append("release_id")
    if manifest.get("schema_version") != "a18-live-bundle-v1":
        errors.append("schema_version")
    if manifest.get("model_version") != "seat-distribution-a18-v1":
        errors.append("model_version")
    if manifest.get("routes") != ["1650", "3330"]:
        errors.append("routes")
    if manifest.get("horizons") != list(range(1, 13)):
        errors.append("horizons")
    if set(tensors) != set(PRODUCTION_TENSORS):
        errors.append("tensor_names")
    for name, (shape, dtype) in PRODUCTION_TENSORS.items():
        if name not in tensors:
            continue
        values = np.asarray(tensors[name])
        if values.shape != shape or str(values.dtype) != dtype:
            errors.append(f"tensor_contract:{name}")
        if np.issubdtype(values.dtype, np.floating) and not np.all(np.isfinite(values)):
            errors.append(f"non_finite:{name}")
        if name.endswith(("_seen", "_fitted")) and np.any((values != 0) & (values != 1)):
            errors.append(f"non_binary:{name}")
    if np.any(np.asarray(tensors["lead_seconds"]) <= 0):
        errors.append("non_positive_lead")

    production_features = tuple(manifest.get("features", {}).get("names", ()))
    production_fields = set(manifest)
    production_tensor_names = set(tensors)
    dev_reasons = {
        "manifest_field_set_equal": production_fields == DEV_MANIFEST_FIELDS,
        "feature_names_equal": production_features == DEV_FEATURE_NAMES,
        "tensor_name_set_equal": production_tensor_names == DEV_TENSORS,
        "top_level_snake_case_in_production": "schema_version" in production_fields,
        "top_level_camel_case_required_by_dev": "bundleSchemaVersion" in DEV_MANIFEST_FIELDS,
        "production_extra_tensors_rejected_by_dev": sorted(production_tensor_names - DEV_TENSORS),
        "dev_required_manifest_fields_missing": sorted(DEV_MANIFEST_FIELDS - production_fields),
    }

    fit_rows = manifest.get("training", {}).get("fit_rows", {})
    tensor_statistics = coefficient_stats(tensors)
    result = {
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "classification": "NON_COMPLIANT_WITH_V4_1",
        "production_contract_valid": not errors,
        "dev_v4_1_drop_in_compatible": False,
        "release_id": manifest.get("release_id"),
        "data_through": manifest.get("data_through"),
        "source_dates": manifest.get("training", {})
        .get("provenance", {})
        .get("document", {})
        .get("source_dates"),
        "fit_rows_total": sum(
            int(count) for route in fit_rows.values() for count in route.values()
        ),
        "files": {
            "manifest.json": {
                "bytes": manifest_file.stat().st_size,
                "sha256": manifest_sha,
                "canonical_json": manifest_bytes == canonical,
            },
            "weights.safetensors": {
                "bytes": weights_file.stat().st_size,
                "sha256": weights_sha,
            },
            "bundle_sha256_manifest_then_weights": exact_bundle_sha,
        },
        "tensors": tensor_statistics,
        "dev_comparison": dev_reasons,
        "all_reported_numbers_finite": all(
            math.isfinite(value)
            for summary in tensor_statistics.values()
            for value in summary.values()
            if isinstance(value, float)
        ),
        "sensitive_hmac_values_in_manifest": bool(
            re.search(r"hmac-sha256:[0-9a-f]{64}", manifest_bytes.decode("utf-8"), re.I)
        ),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
