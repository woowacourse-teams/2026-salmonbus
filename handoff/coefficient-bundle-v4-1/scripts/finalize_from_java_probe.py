#!/usr/bin/env python3
"""Insert only exact pinned-Java golden outputs into a provisional manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import math
from pathlib import Path


SHA256 = re.compile(r"^[0-9a-f]{64}$")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--probe-log", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    return parser.parse_args()


def value_at(text: str, key: str) -> str:
    matches = re.findall(rf"^\s*{re.escape(key)}=(.+)$", text, flags=re.MULTILINE)
    if len(matches) != 1:
        raise ValueError(f"missing_or_duplicate_probe_value:{key}")
    return matches[0].strip()


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def main() -> int:
    args = arguments()
    manifest_path = args.bundle / "manifest.json"
    weights_path = args.bundle / "weights.safetensors"
    before = digest(manifest_path)
    text = args.probe_log.read_text(encoding="utf-8")
    if text.count("V4_1_JAVA_PROBE=PASS") != 1:
        raise ValueError("java_probe_did_not_pass_once")
    expected_full_text = value_at(text, "V4_1_JAVA_EXPECTED_FULL_CHANCE")
    expected_seats_text = value_at(text, "V4_1_JAVA_EXPECTED_SEATS")
    expected_full = float(expected_full_text)
    expected_seats = float(expected_seats_text)
    if not math.isfinite(expected_full) or not math.isfinite(expected_seats):
        raise ValueError("non_finite_java_probe_value")
    golden_digest = value_at(text, "V4_1_JAVA_GOLDEN_VECTOR_DIGEST")
    identity_digest = value_at(text, "V4_1_JAVA_IDENTITY_DIGEST")
    if SHA256.fullmatch(golden_digest) is None or SHA256.fullmatch(identity_digest) is None:
        raise ValueError("invalid_java_probe_digest")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest["identityDigest"] != "0" * 64 or manifest["goldenVectorDigest"] != "0" * 64:
        raise ValueError("manifest_is_not_provisional")
    manifest["goldenVector"]["expectedFullChance"] = "__JAVA_EXPECTED_FULL__"
    manifest["goldenVector"]["expectedSeats"] = "__JAVA_EXPECTED_SEATS__"
    manifest["goldenVectorDigest"] = golden_digest
    manifest["identityDigest"] = identity_digest
    temporary = manifest_path.with_name(".manifest.json.tmp")
    payload = canonical(manifest)
    payload = payload.replace(b'"__JAVA_EXPECTED_FULL__"', expected_full_text.encode("ascii"))
    payload = payload.replace(b'"__JAVA_EXPECTED_SEATS__"', expected_seats_text.encode("ascii"))
    # Reparse to prove the Java lexemes are valid JSON numbers before replacing
    # the only copy of the provisional manifest.
    json.loads(payload)
    temporary.write_bytes(payload)
    os.replace(temporary, manifest_path)
    receipt = {
        "schemaVersion": "v4-1-java-golden-finalization-v1",
        "probeLog": args.probe_log.name,
        "targetDevCommit": "d856d10819bf1d018ad43fa63714cc348f1fc643",
        "manifestBeforeSha256": before,
        "manifestSha256": digest(manifest_path),
        "weightsSha256": digest(weights_path),
        "goldenVectorDigest": golden_digest,
        "identityDigest": identity_digest,
        "expectedFullChance": expected_full,
        "expectedSeats": expected_seats,
    }
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(
        json.dumps(receipt, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"status": "finalized", "manifestSha256": receipt["manifestSha256"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
