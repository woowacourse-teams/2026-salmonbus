#!/usr/bin/env python3

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path


TARGET_COMMIT = "d856d10819bf1d018ad43fa63714cc348f1fc643"


def read(path: Path) -> dict[str, object]:
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    processed = root / "processed"
    manifest_path = root / "bundle" / "manifest.json"
    weights_path = root / "bundle" / "weights.safetensors"
    manifest = read(manifest_path)
    java = read(processed / "java-finalization.json")
    route_seed = read(processed / "route-seed-refresh-receipt.json")
    closure = read(processed / "final-source-closure.json")
    validation_paths = {
        "pythonBundle": "processed/python-bundle-validation.json",
        "seed": "processed/seed-validation.json",
        "temporarySmokeExclusion": "processed/temp-exclusion-negative-test.json",
        "privacy": "processed/privacy-scan.json",
    }
    validation = {
        name: read(root / relative)
        for name, relative in validation_paths.items()
    }
    if any(value.get("status") != "PASS" for value in validation.values()):
        raise ValueError("non_pass_validation_receipt")
    required_logs = {
        "pythonContract": ("processed/python-contract-tests.log", "\nOK\n"),
        "javaProbe": ("processed/java-probe.log", "V4_1_JAVA_PROBE=PASS"),
        "javaVerify": ("processed/java-bundle-verify.log", "V4_1_JAVA_VERIFY=PASS"),
        "javaLoaderOnly": ("processed/java-loader-only.log", "V4_1_JAVA_LOADER_ONLY=PASS"),
        "javaCore": ("processed/java-core-tests.log", "BUILD SUCCESSFUL"),
    }
    for relative, token in required_logs.values():
        if token not in (root / relative).read_text(encoding="utf-8"):
            raise ValueError("required_validation_log_token_missing")
    if (
        manifest.get("sourceCommit") != TARGET_COMMIT
        or java.get("targetDevCommit") != TARGET_COMMIT
        or java.get("manifestSha256") != sha256(manifest_path)
        or java.get("weightsSha256") != sha256(weights_path)
        or manifest.get("identityDigest") != java.get("identityDigest")
        or manifest.get("goldenVectorDigest") != java.get("goldenVectorDigest")
        or route_seed.get("status") != "PASS"
        or route_seed.get("coefficientWeightsChanged") is not False
        or closure.get("classification") != "FINAL_FREEZE_CLOSED"
    ):
        raise ValueError("final_artifact_identity_mismatch")
    receipt_path = processed / "build-receipt.json"
    receipt = read(receipt_path)
    if receipt.get("targetDevCommit") != TARGET_COMMIT:
        raise ValueError("build_target_commit_mismatch")
    bundle = receipt["bundle"]
    assert isinstance(bundle, dict)
    bundle.update(
        {
            "bundleDigest": java["identityDigest"],
            "goldenStatus": "PASS_EXACT_D856_JAVA",
            "goldenVectorDigest": java["goldenVectorDigest"],
            "identityDigest": java["identityDigest"],
            "manifestProvisionalSha256": java["manifestBeforeSha256"],
            "manifestSha256": java["manifestSha256"],
        }
    )
    receipt["validation"] = {
        "status": "PASS",
        "targetDevCommit": TARGET_COMMIT,
        "checks": {
            **{name: relative for name, relative in validation_paths.items()},
            **{name: relative for name, (relative, _token) in required_logs.items()},
        },
        "remoteWritesPerformed": False,
    }
    receipt["nextRequiredStep"] = "documentation and deterministic package only"
    temporary = receipt_path.with_name(".build-receipt.json.validation.tmp")
    temporary.write_text(
        json.dumps(receipt, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True)
        + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, receipt_path)
    print(json.dumps({"status": "PASS", "bundleDigest": java["identityDigest"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
