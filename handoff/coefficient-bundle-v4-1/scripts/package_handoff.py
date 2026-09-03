#!/usr/bin/env python3
"""Build the package manifest and a deterministic colleague handoff ZIP."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import zipfile
from datetime import UTC, datetime
from pathlib import Path


EXCLUDED_PARTS = {"delivery", "__pycache__"}
EXCLUDED_NAMES = {".DS_Store", "manifest.json"}


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    return parser.parse_args()


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(chunk)
    return hasher.hexdigest()


def role(path: Path) -> str:
    if path.parts[0] == "bundle":
        return "coefficient_bundle"
    if path.parts[0] == "seed":
        return "aggregate_backfill_seed"
    if path.parts[0] == "processed":
        return "aggregate_receipt_or_validation"
    if path.parts[0] == "scripts":
        return "reproduction_or_validation_script"
    if path.parts[0] == "raw":
        return "raw_data_policy"
    return "handoff_documentation"


def json_metadata(path: Path) -> dict[str, object]:
    try:
        if path.suffix == ".gz":
            value = json.loads(gzip.decompress(path.read_bytes()))
        elif path.suffix == ".json":
            value = json.loads(path.read_text(encoding="utf-8"))
        else:
            return {}
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return {}
    if not isinstance(value, dict):
        return {}
    result: dict[str, object] = {}
    schema = value.get("schemaVersion", value.get("schema_version"))
    if isinstance(schema, str):
        result["schemaVersion"] = schema
    if isinstance(value.get("rowCount"), int):
        result["rowCount"] = value["rowCount"]
    elif isinstance(value.get("rows"), list):
        result["rowCount"] = len(value["rows"])
    return result


def included_files(root: Path) -> list[Path]:
    result = []
    for path in root.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(root)
        if relative.name in EXCLUDED_NAMES or any(part in EXCLUDED_PARTS for part in relative.parts):
            continue
        if any(part.startswith(".build-derived-") for part in relative.parts):
            continue
        result.append(relative)
    return sorted(result, key=lambda item: item.as_posix().encode("utf-8"))


def main() -> int:
    args = arguments()
    root = args.root.resolve()
    build = json.loads((root / "processed" / "build-receipt.json").read_text(encoding="utf-8"))
    bundle = json.loads((root / "bundle" / "manifest.json").read_text(encoding="utf-8"))
    source = json.loads((root / "processed" / "source-audit.json").read_text(encoding="utf-8"))
    closure = json.loads(
        (root / "processed" / "final-source-closure.json").read_text(encoding="utf-8")
    )
    catchup_routes = closure["selection"]["routeCatchUpForSeedAndMigration"]["routes"]
    files = included_files(root)
    entries = []
    total_bytes = 0
    for relative in files:
        path = root / relative
        size = path.stat().st_size
        total_bytes += size
        entries.append(
            {
                "path": relative.as_posix(),
                "bytes": size,
                "sha256": digest(path),
                "role": role(relative),
                **json_metadata(path),
            }
        )
    manifest = {
        "schemaVersion": "coefficient-bundle-v4-1-handoff-manifest-v1",
        "createdAtUtc": datetime.now(UTC).isoformat(),
        "classification": build["classification"],
        "modelReleaseId": bundle["releaseId"],
        "bundleDigest": bundle["identityDigest"],
        "targetDevCommit": build["targetDevCommit"],
        "featureContractVersion": bundle["featureContractVersion"],
        "v4_1ReleaseQualification": build["training"]["releaseQualification"],
        "sourcePeriod": {
            "coefficientFromDateKst": source["source"]["from_date"],
            "coefficientThroughDateKst": source["source"]["through_date"],
            "coefficientDatePartitions": source["source"]["completed_dates"],
            "fullSourceAuthorityThroughExclusiveUtcByRoute": {
                route: value["boundaryLowerUtc"]
                for route, value in sorted(catchup_routes.items())
            },
            "acceptedBatches": source["totals"]["accepted_record_documents"]
            + sum(
                value["catchUp"]["acceptedRecordDocuments"]
                for value in catchup_routes.values()
            ),
            "acceptedObservations": closure["selection"]["sourceAuthorityObservations"],
            "activePartitionManifestSha256": closure["inventory"]["manifestSha256"],
            "sourceClosureSha256": closure["sourceClosureSha256"],
        },
        "fileCount": len(entries),
        "totalBytes": total_bytes,
        "selfHashExcluded": True,
        "deliveryDirectoryExcluded": True,
        "files": entries,
    }
    manifest_path = root / "manifest.json"
    temporary = root / ".manifest.json.tmp"
    temporary.write_text(
        json.dumps(manifest, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    os.replace(temporary, manifest_path)

    delivery = root / "delivery"
    delivery.mkdir(exist_ok=True)
    archive_name = f"coefficient-bundle-v4-1-{bundle['releaseId']}.zip"
    archive = delivery / archive_name
    if archive.exists() or archive.is_symlink():
        raise ValueError("delivery_archive_already_exists")
    archive_files = included_files(root)
    archive_files.append(Path("manifest.json"))
    archive_files.sort(key=lambda item: item.as_posix().encode("utf-8"))
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for relative in archive_files:
            content = (root / relative).read_bytes()
            info = zipfile.ZipInfo(relative.as_posix(), date_time=(2026, 9, 2, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            mode = 0o755 if os.access(root / relative, os.X_OK) else 0o644
            info.external_attr = mode << 16
            zipped.writestr(info, content, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    archive_digest = digest(archive)
    (delivery / "SHA256SUMS").write_text(
        f"{archive_digest}  {archive_name}\n", encoding="utf-8"
    )
    receipt = {
        "schemaVersion": "coefficient-bundle-v4-1-delivery-receipt-v1",
        "createdAtUtc": datetime.now(UTC).isoformat(),
        "archive": archive_name,
        "bytes": archive.stat().st_size,
        "sha256": archive_digest,
        "fileCount": len(archive_files),
        "modelReleaseId": bundle["releaseId"],
    }
    (delivery / "receipt.json").write_text(
        json.dumps(receipt, ensure_ascii=False, allow_nan=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps({"archive": archive_name, "sha256": archive_digest}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
