#!/usr/bin/env python3
"""Bind the source-side seed to its mandatory target-observation delta contract."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    seed_path = args.root / "seed" / "cell-hourly-aggregate.json.gz"
    receipt_path = args.root / "seed" / "receipt.json"
    document = json.loads(gzip.decompress(seed_path.read_bytes()))
    document["scope"] = "SOURCE_SIDE_THROUGH_TARGET_AUTHORITY"
    document["requiresRdsObservationDeltaBeforeFormalCutover"] = True
    document["rdsObservationDeltaContract"] = "rds-observation-delta-contract.json"
    canonical = json.dumps(
        document,
        ensure_ascii=False,
        allow_nan=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    compressed = gzip.compress(canonical, compresslevel=9, mtime=0)
    temporary = seed_path.with_name(f".{seed_path.name}.scope.tmp")
    temporary.write_bytes(compressed)
    os.replace(temporary, seed_path)
    receipt = json.loads(receipt_path.read_text(encoding="utf-8"))
    receipt.update(
        {
            "compressedBytes": len(compressed),
            "uncompressedBytes": len(canonical),
            "compressedSha256": hashlib.sha256(compressed).hexdigest(),
            "canonicalJsonSha256": hashlib.sha256(canonical).hexdigest(),
            "requiresRdsObservationDeltaBeforeFormalCutover": True,
        }
    )
    temporary_receipt = receipt_path.with_name(".receipt.json.scope.tmp")
    temporary_receipt.write_text(
        json.dumps(receipt, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary_receipt, receipt_path)
    print(json.dumps({"status": "seed-scope-finalized"}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
