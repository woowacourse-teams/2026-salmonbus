#!/usr/bin/env python3
"""Explicit, bounded RDS -> S3 -> local-file rehearsal. Never deletes DB rows.

Run on the worker EC2 with its existing role: sudo python3 preview_statistics_s3.py --execute
This is a raw evaluation sample, NOT a complete archive or the production demand calculation.
Credentials and row contents are never printed. S3 objects and the private temp directory remain.
"""
import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
from urllib.parse import urlsplit
import uuid

BUCKET = "techcourse-project-2026"
QUERY = "SELECT row_to_json(e) FROM (SELECT * FROM forecast_evaluation WHERE scoring_state='SETTLED' ORDER BY arrived_at,vehicle_observation_id LIMIT 10) e;"
MAX_BYTES = 256 * 1024


def connection_environment(path):
    fields = dict(line.strip().split("=", 1) for line in Path(path).read_text().splitlines()
                  if line.startswith(("DB_URL=", "DB_USERNAME=", "DB_PASSWORD=")))
    url = urlsplit(fields["DB_URL"].strip("\"'").removeprefix("jdbc:"))
    env = os.environ.copy()
    env.update(PGHOST=url.hostname, PGPORT=str(url.port or 5432), PGDATABASE=url.path[1:],
               PGUSER=fields["DB_USERNAME"].strip("\"'"), PGPASSWORD=fields["DB_PASSWORD"].strip("\"'"),
               PGSSLMODE="require", PGCONNECT_TIMEOUT="5", PGAPPNAME="sal175-readonly-preview",
               PGOPTIONS="-c default_transaction_read_only=on -c statement_timeout=1500 -c lock_timeout=100")
    return env


def sql(query, env):
    result = subprocess.run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1"], input=query,
                            text=True, capture_output=True, env=env, timeout=8)
    if result.returncode:
        raise RuntimeError("Read-only query failed; details suppressed")
    if len(result.stdout.encode()) > MAX_BYTES:
        raise RuntimeError("Sample size limit exceeded")
    return result.stdout


def bounded_plan(node):
    nodes = [node]
    while nodes:
        current = nodes.pop()
        if current["Node Type"] in ("Seq Scan", "Sort", "Incremental Sort"):
            return False
        nodes.extend(current.get("Plans", []))
    return node.get("Total Cost", float("inf")) < 100


def summarize(rows):
    return {"rows": len(rows), "states": dict(sorted(Counter(row["scoring_state"] for row in rows).items())),
            "arrival_seats_sum": sum(row["seats_on_arrival"] for row in rows if row.get("seats_on_arrival") is not None)}


def aws(arguments):
    env = os.environ.copy()
    env["AWS_MAX_ATTEMPTS"] = "2"
    result = subprocess.run(["aws", "s3api", *arguments, "--region", "ap-northeast-2",
                             "--cli-connect-timeout", "5", "--cli-read-timeout", "15",
                             "--no-cli-pager", "--no-cli-auto-prompt"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=env, timeout=45)
    if result.returncode:
        raise RuntimeError("S3 command failed; details suppressed")


def run():
    env = connection_environment("/etc/salmonbus/worker.env")
    plan = json.loads(sql("EXPLAIN (FORMAT JSON) " + QUERY, env))[0]["Plan"]
    if not bounded_plan(plan):
        raise RuntimeError("Unexpected scan plan; no sample read")
    raw = [json.loads(line) for line in sql(QUERY, env).splitlines() if line]
    # The plan must use the partial settled index; never scan all rows searching for settled data.
    rows = [row for row in raw if row["scoring_state"] != "PENDING" and row.get("scored_at")]
    if not rows or len(rows) > 10:
        raise RuntimeError("No bounded completed sample")
    payload = "".join(json.dumps(row, separators=(",", ":")) + "\n" for row in rows).encode()
    if len(payload) > MAX_BYTES:
        raise RuntimeError("Serialized sample size limit exceeded")
    directory = Path(tempfile.mkdtemp(prefix="sal175-s3-preview-"))
    data = directory / "evaluations.jsonl"
    data.write_bytes(payload)
    digest = hashlib.sha256(payload).hexdigest()
    manifest = {"format": "sal175-evaluation-preview-v1", "complete_archive": False,
                "deletion_authorized": False, "file": data.name, "bytes": len(payload),
                "sha256": digest, "summary": summarize(rows)}
    manifest_path = directory / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, sort_keys=True))
    prefix = "salmonbus-be/checks/sal175-real-data/" + str(uuid.uuid4()) + "/"
    for path in (data, manifest_path):
        aws(["put-object", "--bucket", BUCKET, "--key", prefix + path.name, "--body", str(path),
             "--if-none-match", "*", "--checksum-algorithm", "SHA256", "--tagging",
             "Service=techcourse&Role=techcourse-etc&ProjectTeam=salmonbus"])
    downloaded_manifest = directory / "downloaded-manifest.json"
    aws(["get-object", "--bucket", BUCKET, "--key", prefix + manifest_path.name,
         "--range", "bytes=0-" + str(MAX_BYTES), str(downloaded_manifest)])
    if downloaded_manifest.read_bytes() != manifest_path.read_bytes():
        raise RuntimeError("Manifest differs")
    downloaded = directory / "downloaded-evaluations.jsonl"
    aws(["get-object", "--bucket", BUCKET, "--key", prefix + data.name,
         "--range", "bytes=0-" + str(len(payload)), str(downloaded)])
    received = downloaded.read_bytes()
    if received != payload or hashlib.sha256(received).hexdigest() != digest:
        raise RuntimeError("Downloaded sample differs")
    restored_summary = summarize([json.loads(line) for line in received.splitlines()])
    if restored_summary != manifest["summary"]:
        raise RuntimeError("File-based summary differs")
    print(json.dumps({"s3": "s3://" + BUCKET + "/" + prefix, "directory": str(directory),
                      "sha256": digest, "bytes": len(payload), "summary": restored_summary,
                      "roundtrip_verified": True, "db_rows_deleted": 0,
                      "production_demand_calculation_verified": False}, sort_keys=True))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--execute", action="store_true", required=True)
    parser.parse_args()
    try:
        run()
    except Exception:
        raise SystemExit("Preview failed. No DB deletion. Partial private files/objects may remain.")
