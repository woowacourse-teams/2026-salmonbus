#!/usr/bin/env python3
"""Scan durable data artifacts for secret, identifier, raw-key, and placeholder leaks."""

from __future__ import annotations

import argparse
import gzip
import json
import os
import re
from pathlib import Path


PATTERNS = {
    "hmacValue": re.compile(rb"hmac-sha256:[0-9a-f]{64}", re.IGNORECASE),
    "awsAccessKey": re.compile(rb"(?:AKIA|ASIA)[0-9A-Z]{16}"),
    "privateKey": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "rawObjectKey": re.compile(rb"(?:records|raw)/route=[0-9]+/dt=[0-9]{4}-[0-9]{2}-[0-9]{2}/"),
    "rowVehicleValue": re.compile(rb'\"(?:vehId|plateNo|vehicleHmac)\"\s*:\s*\"[^\"]+\"', re.IGNORECASE),
}
PLACEHOLDERS = {
    "zeroDigest": re.compile(rb'\"(?:identityDigest|goldenVectorDigest)\"\s*:\s*\"0{64}\"'),
    "placeholderWord": re.compile(rb"\b(?:TODO|PLACEHOLDER|CHANGEME)\b", re.IGNORECASE),
}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def content(path: Path) -> bytes:
    raw = path.read_bytes()
    return gzip.decompress(raw) if path.suffix == ".gz" else raw


def main() -> int:
    args = arguments()
    roots = [args.root / name for name in ("bundle", "processed", "seed")]
    findings = {name: [] for name in (*PATTERNS, *PLACEHOLDERS)}
    scanned = 0
    scanned_bytes = 0
    for base in roots:
        for path in sorted(base.rglob("*")):
            if not path.is_file() or path == args.output:
                continue
            payload = content(path)
            scanned += 1
            scanned_bytes += len(payload)
            relative = path.relative_to(args.root).as_posix()
            for name, pattern in {**PATTERNS, **PLACEHOLDERS}.items():
                if pattern.search(payload):
                    findings[name].append(relative)
    passed = all(not paths for paths in findings.values())
    receipt = {
        "schemaVersion": "v4-1-durable-artifact-privacy-scan-v1",
        "status": "PASS" if passed else "FAIL",
        "scope": ["bundle/", "processed/", "seed/"],
        "filesScanned": scanned,
        "uncompressedBytesScanned": scanned_bytes,
        "findings": findings,
        "rawRowsPersisted": False,
        "vehicleIdentifiersPersisted": False,
        "vehicleHmacsPersisted": False,
        "plateValuesPersisted": False,
        "secretsPersisted": False,
        "placeholdersPersisted": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_name(f".{args.output.name}.tmp")
    temporary.write_text(
        json.dumps(receipt, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, args.output)
    if not passed:
        raise SystemExit(1)
    print(json.dumps({"status": "PASS", "filesScanned": scanned}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
