#!/usr/bin/env python3
"""Refresh only the aggregate seed from the d856 route-specific S3 authority closure."""

from __future__ import annotations

import argparse
import json
import shutil
import tempfile
import time
from datetime import datetime
from pathlib import Path

import audit_source
from current_public.evaluation.pipeline import load_protocol

from build_v41 import (
    KST,
    PRIMARY_S,
    ROUTES,
    build_profiles,
    checkpoint,
    file_sha256,
    generation_boundaries,
    load_source,
    materialize_route,
    peak_rss_mib,
    route_rosters,
    safe_write_json,
    to_us,
    write_seed,
)


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--region", default="ap-northeast-2")
    parser.add_argument("--from-date", required=True)
    parser.add_argument("--through-date", required=True)
    parser.add_argument("--protocol", type=Path, required=True)
    parser.add_argument("--source-audit", type=Path, required=True)
    parser.add_argument("--label-watermark-audit", type=Path, required=True)
    parser.add_argument("--catchup-audit", type=Path, required=True)
    parser.add_argument("--route-catchup-audit", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument(
        "--checkpoint-script",
        type=Path,
        default=Path("/Users/idonghun/paseo/workspaces/2026-salmonbus/checkpoint.sh"),
    )
    return parser.parse_args()


def main() -> int:
    args = arguments()
    started = time.monotonic()
    output = args.output.resolve()
    source_audit = json.loads(args.source_audit.read_text(encoding="utf-8"))
    watermark_audit = json.loads(args.label_watermark_audit.read_text(encoding="utf-8"))
    catchup_audit = json.loads(args.catchup_audit.read_text(encoding="utf-8"))
    route_audit = json.loads(args.route_catchup_audit.read_text(encoding="utf-8"))
    training = json.loads(
        (output / "processed" / "training-receipt.json").read_text(encoding="utf-8")
    )
    selected_g = int(training["selectedGSeconds"])
    selected_s = int(training["selectedSSeconds"])
    if selected_s != PRIMARY_S:
        raise RuntimeError("unexpected_primary_settlement_guard")
    protocol = load_protocol(args.protocol)
    rosters, route_reference_digest = route_rosters(protocol)
    dates = audit_source.calendar_days(args.from_date, args.through_date)
    old_seed_receipt = json.loads((output / "seed" / "receipt.json").read_text())
    work = Path(tempfile.mkdtemp(prefix=".seed-refresh-derived-", dir=output))
    try:
        compact, vehicle_counts, source_receipt = load_source(
            args,
            protocol,
            source_audit,
            watermark_audit,
            catchup_audit,
            route_audit,
            seed_only=True,
        )
        cutoff_by_route = {
            route: to_us(
                datetime.fromisoformat(
                    route_audit["routes"][route]["boundaryLowerUtc"].replace(
                        "Z", "+00:00"
                    )
                )
            )
            for route in ROUTES
        }
        materials = {}
        lag_rows = {}
        for route in ROUTES:
            materials[route] = materialize_route(
                route,
                compact[route],
                vehicle_counts[route],
                rosters[route],
                dates,
                work,
                lag_rows,
                cutoff_by_route[route],
            )
            del compact[route]
        if lag_rows:
            raise RuntimeError("seed_refresh_opened_coefficient_rows")
        boundaries = generation_boundaries(dates[0], max(cutoff_by_route.values()))
        primary = build_profiles(
            f"g{selected_g}-s{selected_s}-route-refresh",
            selected_g,
            selected_s,
            materials,
            rosters,
            boundaries,
        )
        new_seed_receipt = write_seed(
            output,
            primary,
            materials,
            boundaries,
            cutoff_by_route,
            route_reference_digest,
        )
        safe_write_json(
            output / "processed" / "route-seed-source-load-receipt.json",
            source_receipt,
        )
        receipt = {
            "schemaVersion": "v4-1-route-specific-seed-refresh-receipt-v1",
            "status": "PASS",
            "source": source_receipt,
            "routeCatchUpAuditSha256": file_sha256(args.route_catchup_audit),
            "selectedGSeconds": selected_g,
            "selectedSSeconds": selected_s,
            "oldSeed": old_seed_receipt,
            "newSeed": new_seed_receipt,
            "coefficientWeightsChanged": False,
            "coefficientOrQualityRowsOpenedFromCatchUp": 0,
            "tempForecastOrStatisticsRead": False,
            "remoteWriteOrTransfer": False,
            "runtime": {
                "durationSeconds": round(time.monotonic() - started, 3),
                "peakRssMiB": peak_rss_mib(),
            },
        }
        safe_write_json(
            output / "processed" / "route-seed-refresh-receipt.json", receipt
        )
        cell_receipt_path = output / "processed" / "cell-backfill-receipt.json"
        cell_receipt = json.loads(cell_receipt_path.read_text(encoding="utf-8"))
        cell_receipt["originalGlobalSeedStatus"] = "SUPERSEDED_BY_D856_ROUTE_SPECIFIC_REFRESH"
        cell_receipt["routeSpecificSeed"] = new_seed_receipt
        safe_write_json(cell_receipt_path, cell_receipt)
        build_receipt_path = output / "processed" / "build-receipt.json"
        build_receipt = json.loads(build_receipt_path.read_text(encoding="utf-8"))
        build_receipt["routeSpecificSeedRefresh"] = {
            "receipt": "processed/route-seed-refresh-receipt.json",
            "seedSha256": new_seed_receipt["compressedSha256"],
            "weightsChanged": False,
        }
        safe_write_json(build_receipt_path, build_receipt)
        checkpoint(
            args.checkpoint_script,
            "d856 route별 S3 authority로 aggregate seed refresh 완료; weights·remote 변경 없음",
        )
        print(
            json.dumps(
                {
                    "status": "PASS",
                    "seedSha256": new_seed_receipt["compressedSha256"],
                },
                sort_keys=True,
            )
        )
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    raise SystemExit(main())
