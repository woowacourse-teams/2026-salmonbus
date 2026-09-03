#!/usr/bin/env python3
"""Set the deterministic sanitized feature-parity fixture before Java probe."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    path = args.bundle / "manifest.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest["identityDigest"] != "0" * 64 or manifest["goldenVectorDigest"] != "0" * 64:
        raise ValueError("golden_fixture_can_only_prepare_provisional_manifest")
    active_commit = "d856d10819bf1d018ad43fa63714cc348f1fc643"
    manifest["sourceCommit"] = active_commit
    manifest["goldenVector"] = {
        "featureVector": [1.0] + [0.0] * 30,
        "modelRoute": "3330",
        "stopsAhead": 6,
        "currentSeats": 12,
        "capacity": 44,
        "expectedFullChance": 0.0,
        "expectedSeats": 0.0,
    }
    payload = json.dumps(
        manifest,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    temporary = path.with_name(".manifest.json.prepare.tmp")
    temporary.write_bytes(payload)
    os.replace(temporary, path)
    build_receipt_path = args.bundle.parent / "processed" / "build-receipt.json"
    if build_receipt_path.is_file():
        receipt = json.loads(build_receipt_path.read_text(encoding="utf-8"))
        receipt["targetDevCommit"] = active_commit
        receipt.setdefault("implementation", {})[
            "activeAuthorityModelAndSchemaDiffFromTrainingBasis"
        ] = "NONE"
        receipt["implementation"]["activeAuthorityUpdateReceipt"] = (
            "processed/dev-authority-update.json"
        )
        temporary_receipt = build_receipt_path.with_name(".build-receipt.json.authority.tmp")
        temporary_receipt.write_text(
            json.dumps(receipt, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n",
            encoding="utf-8",
        )
        os.replace(temporary_receipt, build_receipt_path)
    print(json.dumps({"status": "golden-fixture-prepared"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
