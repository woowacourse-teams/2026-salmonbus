"""Package a Les serving release only after independent validation receipts exist.

This tool does not run or invent validation. The offline bundle remains unchanged.
Each receipt binds a PASS result and non-empty evidence file to the source manifest.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime
from pathlib import Path

CHECKS = ("feature_parity", "historical_replay", "settlement", "runtime_validation")
ROUTES = ["3330", "1650", "9007", "9300", "6011", "3000", "5600", "3500"]


def digest(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def prepare(source: Path, output: Path, release_id: str, receipts: Path) -> None:
    import re
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}", release_id):
        raise ValueError("invalid release id")
    if output.exists():
        raise ValueError("output must be a new immutable release directory")
    source_bytes = (source / "manifest.json").read_bytes()
    manifest = json.loads(source_bytes)
    if manifest.get("schema") != "les-inference-bundle-v1" or manifest.get("serving_ready") is not False:
        raise ValueError("expected offline Les bundle")
    source_digest = digest(source_bytes)
    contents: dict[str, bytes] = {}
    for name, expected in manifest["files"].items():
        if Path(name).name != name or (source / name).is_symlink():
            raise ValueError("invalid source file")
        content = (source / name).read_bytes()
        if digest(content) != expected:
            raise ValueError(f"source digest mismatch: {name}")
        contents[name] = content
    for check in CHECKS:
        content = (receipts / f"verification-{check}.json").read_bytes()
        receipt = json.loads(content)
        if not (receipt.get("schema") == "les-serving-verification-v1"
                and receipt.get("check") == check and receipt.get("status") == "PASS"
                and receipt.get("source_manifest_sha256") == source_digest
                and receipt.get("feature_contract_version") == "les-online-v1"
                and type(receipt.get("verified_cases")) is int and receipt["verified_cases"] > 0):
            raise ValueError(f"missing successful source-bound verification: {check}")
        evidence_name = receipt.get("evidence_file", "")
        if evidence_name != f"evidence-{check}.json":
            raise ValueError(f"invalid evidence name: {check}")
        evidence = (receipts / evidence_name).read_bytes()
        if not isinstance(json.loads(evidence), dict) or digest(evidence) != receipt.get("evidence_sha256"):
            raise ValueError(f"evidence digest mismatch: {check}")
        contents[f"verification-{check}.json"] = content
        contents[evidence_name] = evidence
    # Date includes the latest observation/event used by any frozen table.
    dates = []
    for route in ROUTES:
        stats = json.loads(contents[f"statistics-{route}.json"])
        dates.extend(datetime.fromisoformat(stats[key]) for key in ("events_known_through", "observations_through"))
    if any(value.tzinfo is None for value in dates):
        raise ValueError("statistics cutoff must include timezone")
    contents["verification-source-manifest.json"] = source_bytes
    manifest["serving_ready"] = True
    manifest["pending"] = []
    manifest["runtime"] = {
        "release_id": release_id,
        "feature_contract_version": "les-online-v1",
        "data_through": max(dates).isoformat(),
        "source_manifest_sha256": source_digest,
        "routes": ROUTES,
        "scope": "weekday-rush-6-10-17-21-horizon-1-12",
    }
    manifest["files"] = {name: digest(content) for name, content in contents.items()}
    # Publish only after all inputs were validated; cleanup a partial local copy on write failure.
    output.mkdir(parents=True)
    try:
        for name, content in contents.items():
            (output / name).write_bytes(content)
        (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    except BaseException:
        shutil.rmtree(output)
        raise


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--receipts", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.source, args.output, args.release_id, args.receipts)


if __name__ == "__main__":
    main()
