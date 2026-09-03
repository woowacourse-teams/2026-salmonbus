#!/usr/bin/env python3
"""Independent Python validation of the finalized five-tensor v4-1 bundle."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
from pathlib import Path

import numpy as np
from safetensors.numpy import load_file

from v41_model import (
    FEATURE_NAMES,
    HORIZONS,
    ROUTES,
    TENSOR_SPECS,
    ZERO_FEATURE_AXES,
    canonical_json,
    score_one,
)


KNOWN_FIELDS = {
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
SHA256 = re.compile(r"^[0-9a-f]{64}$")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--contexts", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def java_number(value: float) -> str:
    # Golden fixtures deliberately avoid the Java/Python exponent-format boundary.
    # For finite ordinary magnitudes both runtimes use the same shortest round-trip
    # representation. Keep signed zero and integral .0 explicit.
    if not math.isfinite(value):
        raise ValueError("non_finite_golden_number")
    text = repr(float(value))
    if "e" in text or "E" in text:
        raise ValueError("golden_number_requires_java_exponent_formatter")
    return text


def golden_text(
    golden: dict[str, object], expected_full_text: str, expected_seats_text: str
) -> bytes:
    lines = [
        ",".join(java_number(float(value)) for value in golden["featureVector"]),
        str(golden["modelRoute"]),
        str(int(golden["stopsAhead"])),
        str(int(golden["currentSeats"])),
        str(int(golden["capacity"])),
        expected_full_text,
        expected_seats_text,
    ]
    return "\n".join(lines).encode("utf-8")


def identity_text(manifest: dict[str, object]) -> bytes:
    constants = manifest["normalizationConstants"]
    normalization = ",".join(
        f"{key}={java_number(float(constants[key]))}" for key in sorted(constants)
    )
    return "\n".join(
        (
            str(manifest["featureContractVersion"]),
            str(manifest["sourceCommit"]),
            str(manifest["modelVersion"]),
            str(manifest["routeReference"]["version"]),
            str(manifest["routeReference"]["digest"]),
            str(manifest["weightsDigest"]),
            ",".join(str(value) for value in manifest["featureNames"]),
            normalization,
            str(manifest["timeSlotSource"]),
            str(manifest["capacityPolicy"]),
            str(manifest["cellStatisticsPolicy"]),
            str(manifest["goldenVectorDigest"]),
        )
    ).encode("utf-8")


def main() -> int:
    args = arguments()
    manifest_path = args.bundle / "manifest.json"
    weights_path = args.bundle / "weights.safetensors"
    manifest_bytes = manifest_path.read_bytes()
    manifest = json.loads(manifest_bytes)
    full_lexeme = re.search(rb'"expectedFullChance":([^,}]+)', manifest_bytes)
    seats_lexeme = re.search(rb'"expectedSeats":([^,}]+)', manifest_bytes)
    if full_lexeme is None or seats_lexeme is None:
        raise ValueError("missing_golden_numeric_lexeme")
    expected_full_text = full_lexeme.group(1).decode("ascii")
    expected_seats_text = seats_lexeme.group(1).decode("ascii")
    canonical_candidate = json.loads(manifest_bytes)
    canonical_candidate["goldenVector"]["expectedFullChance"] = "__JAVA_EXPECTED_FULL__"
    canonical_candidate["goldenVector"]["expectedSeats"] = "__JAVA_EXPECTED_SEATS__"
    expected_canonical = canonical_json(canonical_candidate)
    expected_canonical = expected_canonical.replace(
        b'"__JAVA_EXPECTED_FULL__"', expected_full_text.encode("ascii")
    ).replace(
        b'"__JAVA_EXPECTED_SEATS__"', expected_seats_text.encode("ascii")
    )
    checks: dict[str, object] = {}
    checks["canonicalJsonNoFinalLf"] = manifest_bytes == expected_canonical
    checks["topLevel19KnownFields"] = len(manifest) == 19 and set(manifest) == KNOWN_FIELDS
    checks["schemaVersion"] = manifest["bundleSchemaVersion"] == "a18-live-bundle-v1"
    checks["modelVersion"] = manifest["modelVersion"] == "seat-distribution-a18-v1"
    checks["routeOrder"] = manifest["routes"] == list(ROUTES)
    checks["horizonOrder"] = manifest["horizonStops"] == list(HORIZONS)
    checks["featureOrder"] = manifest["featureNames"] == list(FEATURE_NAMES)
    checks["normalization"] = manifest["normalizationConstants"] == {
        "largestSeatCount": 68.0,
        "lowSeatBand": 20.0,
    }
    checks["weightsDigest"] = sha256(weights_path) == manifest["weightsDigest"]
    checks["routeReferenceDigestShape"] = SHA256.fullmatch(
        manifest["routeReference"]["digest"]
    ) is not None
    checks["sourceCommitShape"] = re.fullmatch(r"[0-9a-f]{40}", manifest["sourceCommit"]) is not None

    tensors = load_file(str(weights_path))
    checks["tensorNames"] = set(tensors) == set(TENSOR_SPECS)
    tensor_receipt = {}
    for name, (dtype_name, shape) in TENSOR_SPECS.items():
        tensor = tensors[name]
        expected_dtype = np.float64 if dtype_name == "F64" else np.uint8
        declaration = manifest["tensors"][name]
        tensor_checks = {
            "shape": tuple(tensor.shape) == shape and declaration["shape"] == list(shape),
            "dtype": tensor.dtype == expected_dtype and declaration["dtype"] == dtype_name,
            "finite": bool(np.all(np.isfinite(tensor))) if dtype_name == "F64" else True,
            "binary": bool(np.all((tensor == 0) | (tensor == 1))) if dtype_name == "U8" else True,
        }
        tensor_receipt[name] = {
            **tensor_checks,
            "minimum": float(np.min(tensor)),
            "maximum": float(np.max(tensor)),
            "nonzero": int(np.count_nonzero(tensor)),
        }
        if not all(tensor_checks.values()):
            raise ValueError(f"tensor_contract_failure:{name}")
    zero_bits = np.asarray([0.0], dtype=np.float64).view(np.uint64)[0]
    zero_contract = True
    for name in ("hurdle_coefficients", "sign_coefficients", "bin_coefficients"):
        values = tensors[name][..., list(ZERO_FEATURE_AXES)]
        zero_contract = zero_contract and bool(
            np.all(values.view(np.uint64) == zero_bits)
        )
    fitted = tensors["bin_fitted"]
    bins = tensors["bin_coefficients"]
    unfitted_zero = True
    for index in np.argwhere(fitted == 0):
        coefficients = bins[tuple(index)]
        unfitted_zero = unfitted_zero and bool(
            np.all(coefficients.view(np.uint64) == zero_bits)
        )
    checks["constantFeatureCoefficientsPositiveZero"] = zero_contract
    checks["unfittedBinCoefficientsPositiveZero"] = unfitted_zero

    golden = manifest["goldenVector"]
    measured_golden = hashlib.sha256(
        golden_text(golden, expected_full_text, expected_seats_text)
    ).hexdigest()
    measured_identity = hashlib.sha256(identity_text(manifest)).hexdigest()
    checks["goldenDigest"] = measured_golden == manifest["goldenVectorDigest"]
    checks["identityDigest"] = measured_identity == manifest["identityDigest"]
    route_axis = ROUTES.index(golden["modelRoute"])
    horizon_axis = HORIZONS.index(int(golden["stopsAhead"]))
    raw, expected, pmf = score_one(
        np.asarray(golden["featureVector"], dtype=np.float64),
        int(golden["currentSeats"]),
        int(golden["capacity"]),
        tensors["hurdle_coefficients"][route_axis, horizon_axis],
        tensors["anchor_coefficients"][route_axis, horizon_axis],
        tensors["sign_coefficients"][route_axis, horizon_axis],
        tensors["bin_coefficients"][route_axis, horizon_axis],
        tensors["bin_fitted"][route_axis, horizon_axis],
    )
    checks["pythonGoldenFullParity"] = abs(raw - float(golden["expectedFullChance"])) <= 1e-12
    checks["pythonGoldenExpectedParity"] = abs(expected - float(golden["expectedSeats"])) <= 1e-12
    checks["goldenPmf71FiniteRangeSumP0"] = (
        pmf.shape == (71,)
        and bool(np.all(np.isfinite(pmf)))
        and bool(np.all((pmf >= 0.0) & (pmf <= 1.0)))
        and abs(float(np.sum(pmf)) - 1.0) <= 1e-12
        and abs(float(pmf[0]) - raw) <= 1e-12
    )

    contexts_document = json.loads(args.contexts.read_text(encoding="utf-8"))
    context_receipts = []
    for index, context in enumerate(contexts_document["contexts"]):
        route_axis = ROUTES.index(context["modelRoute"])
        horizon_axis = HORIZONS.index(int(context["stopsAhead"]))
        actual_raw, actual_expected, actual_pmf = score_one(
            np.asarray(context["featureVector"], dtype=np.float64),
            int(context["currentSeats"]),
            int(context["capacity"]),
            tensors["hurdle_coefficients"][route_axis, horizon_axis],
            tensors["anchor_coefficients"][route_axis, horizon_axis],
            tensors["sign_coefficients"][route_axis, horizon_axis],
            tensors["bin_coefficients"][route_axis, horizon_axis],
            tensors["bin_fitted"][route_axis, horizon_axis],
        )
        passed = (
            math.isfinite(actual_raw)
            and math.isfinite(actual_expected)
            and np.all(np.isfinite(actual_pmf))
            and np.all((actual_pmf >= 0.0) & (actual_pmf <= 1.0))
            and abs(float(np.sum(actual_pmf)) - 1.0) <= 1e-12
            and abs(float(actual_pmf[0]) - actual_raw) <= 1e-12
            and abs(actual_raw - float(context["finalRefitRawFullChance"])) <= 1e-12
            and abs(actual_expected - float(context["finalRefitExpectedSeats"])) <= 1e-12
        )
        context_receipts.append(
            {
                "context": index + 1,
                "route": context["modelRoute"],
                "horizon": context["stopsAhead"],
                "fullChance": actual_raw,
                "expectedSeats": actual_expected,
                "pmfSum": float(np.sum(actual_pmf)),
                "status": "PASS" if passed else "FAIL",
            }
        )
    checks["deidentifiedRuntimeContexts"] = all(
        item["status"] == "PASS" for item in context_receipts
    )
    if not all(bool(value) for value in checks.values()):
        failed = [key for key, value in checks.items() if not value]
        raise ValueError("validation_failed:" + ",".join(failed))
    receipt = {
        "schemaVersion": "v4-1-independent-python-bundle-validation-v1",
        "status": "PASS",
        "manifestSha256": sha256(manifest_path),
        "weightsSha256": sha256(weights_path),
        "releaseId": manifest["releaseId"],
        "checks": checks,
        "tensors": tensor_receipt,
        "golden": {
            "rawFullChance": raw,
            "expectedSeats": expected,
            "pmfSum": float(np.sum(pmf)),
            "pmf0": float(pmf[0]),
        },
        "runtimeContexts": context_receipts,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(
        json.dumps(receipt, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, args.output)
    print(json.dumps({"status": "PASS", "releaseId": manifest["releaseId"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
