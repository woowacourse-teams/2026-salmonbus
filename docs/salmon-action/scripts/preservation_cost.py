"""도착 스냅샷을 얼마나 모을 수 있는지 센다.

보존 기간, 정류장 수, 수집 간격은 여기서 정하지 않는다. 그 값은 인자로만 받고,
worker 설정 파일에는 쓰지 않는다. 출력은 호출 수와 행 수뿐이다.
"""

import argparse
import gzip
import json
import math
import os
import stat
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WORKER_YML = ROOT / "backend/worker-app/src/main/resources/application.yml"
RATE_LIMIT_JAVA = (
    ROOT / "backend/business/arrivals/src/main/java/com/gustler/backend/arrivals/domain/ArrivalRateLimit.java"
)
RUNTIME_JAVA = (
    ROOT
    / "backend/worker-app/src/main/java/com/gustler/backend/worker/arrival/ArrivalRuntimeConfiguration.java"
)
EXTRACT_SH = ROOT / "docs/salmon-action/scripts/extract_offline_input.sh"
INPUT_FOLDER = (
    ROOT / "backend/api-app/src/test/java/com/gustler/backend/api/salmon/offline/InputFolder.java"
)
EXAMPLE_JSON = ROOT / "docs/salmon-action/offline/preservation-inputs.example.json"

# ArrivalRateLimit.acquire 와 같은 보충이다. 그 식의 줄이 바뀌면 self-test가 실패한다.
REFILL_LINE = (
    "tokens = Math.min(burst, tokens + Math.max(0, nowNanos - lastRefill) / 1e9 * callsPerSecond);"
)
# burst 자리는 queueCapacity이고, literal 2는 saveAttempts이다.
POLICY_LINE = "10_000, quota.dailyLimit() / 86_400.0, properties.queueCapacity(), 2);"

UNITS = {"s": 1, "m": 60, "h": 3600, "d": 86400}


def parse_duration(text):
    if text is None or len(text) < 2 or text[-1] not in UNITS or not text[:-1].isdigit():
        raise argparse.ArgumentTypeError(f"기간은 40s, 10m, 1h, 7d 꼴이어야 한다: {text}")
    return int(text[:-1]) * UNITS[text[-1]]


def acquire(tokens, last_refill, now_nanos, calls_per_second, burst):
    tokens = min(float(burst), tokens + max(0, now_nanos - last_refill) / 1e9 * calls_per_second)
    last_refill = now_nanos
    if tokens >= 1:
        return tokens - 1, last_refill, 0
    wait = int(math.ceil((1 - tokens) / calls_per_second * 1e9))
    return tokens, last_refill, wait


def eager_calls(daily_limit, burst, window_seconds):
    """창 안에서 속도 제한이 허용하는 최대 호출 수. 버킷은 가득 찬 상태에서 시작한다."""
    calls_per_second = daily_limit / 86400.0
    tokens = float(burst)
    last_refill = 0
    now = 0
    window_nanos = window_seconds * 1_000_000_000
    count = 0
    while now < window_nanos:
        tokens, last_refill, wait = acquire(tokens, last_refill, now, calls_per_second, burst)
        if wait == 0:
            count += 1
            continue
        now += wait
    return count


def slipping_poll(daily_limit, burst, window_seconds, stations, interval_seconds):
    """간격마다 정류장 수만큼 호출한다. 토큰이 없으면 기다리고, 늦어진 회차는 건너뛰지 않고 밀린다."""
    if stations == 0:
        return []
    calls_per_second = daily_limit / 86400.0
    tokens = float(burst)
    last_refill = 0
    now = 0
    window_nanos = window_seconds * 1_000_000_000
    interval_nanos = interval_seconds * 1_000_000_000
    next_round = 0
    writes = []
    while next_round < window_nanos:
        if now < next_round:
            now = next_round
        if now >= window_nanos:
            break
        finished = True
        for _ in range(stations):
            while True:
                if now >= window_nanos:
                    finished = False
                    break
                tokens, last_refill, wait = acquire(tokens, last_refill, now, calls_per_second, burst)
                if wait == 0:
                    writes.append(now)
                    break
                now += wait
            if not finished:
                break
        if not finished:
            break
        next_round += interval_nanos
    return writes


def rows_present(writes, extract_nanos, validity_seconds, retain_seconds):
    """나이가 validity+retention 이하인 행만 남아 있다. stale_at == cutoff 인 행은 삭제되지 않는다."""
    limit = (validity_seconds + retain_seconds) * 1_000_000_000
    return sum(1 for written in writes if extract_nanos - written <= limit)


def ticks_in(window_seconds, interval_seconds):
    if interval_seconds <= 0 or window_seconds <= 0:
        return 0
    return (window_seconds - 1) // interval_seconds + 1


def estimate(stations, interval_seconds, window_seconds, retain_seconds, extract_after_seconds,
             bytes_per_row, daily_limit, burst, validity_seconds, refresh_seconds,
             product_retention_seconds):
    if interval_seconds <= 0 or window_seconds <= 0 or stations < 0 or bytes_per_row < 0:
        raise ValueError("간격, 창, 정류장 수, 바이트를 확인해야 한다")
    if retain_seconds < 0 or extract_after_seconds < 0 or daily_limit < 1 or burst < 1:
        raise ValueError("보존 기간, 추출 지연, 하루 한도, burst를 확인해야 한다")
    if validity_seconds <= 0 or refresh_seconds <= 0:
        raise ValueError("validity와 refresh-interval은 0보다 커야 한다")
    effective = max(interval_seconds, refresh_seconds)
    writes = slipping_poll(daily_limit, burst, window_seconds, stations, effective)
    present = rows_present(
        writes,
        (window_seconds + extract_after_seconds) * 1_000_000_000,
        validity_seconds,
        retain_seconds,
    )
    requested = stations * ticks_in(window_seconds, effective)
    return {
        "acceptedCalls": len(writes),
        "assumedBytesPerRow": bytes_per_row,
        "effectiveIntervalSeconds": effective,
        "extractAfterSeconds": extract_after_seconds,
        "fitsInSmoother": len(writes) == requested,
        "intervalRaisedToRefresh": effective != interval_seconds,
        "payloadBytesPresent": present * bytes_per_row,
        "productRetentionSeconds": product_retention_seconds,
        "requestedCalls": requested,
        "retainForSeconds": retain_seconds,
        "rowsPresent": present,
        "smootherCapacity": eager_calls(daily_limit, burst, window_seconds),
        "userTrafficIncluded": False,
        "validitySeconds": validity_seconds,
    }


def arrivals_block(text):
    lines = text.splitlines()
    start = next(index for index, line in enumerate(lines) if line.startswith("arrivals:"))
    block = []
    for line in lines[start + 1:]:
        if line and not line[0].isspace():
            break
        block.append(line)
    return block


def field(block, name):
    prefix = name + ":"
    for raw in block:
        stripped = raw.strip()
        if stripped.startswith(prefix):
            value = stripped[len(prefix):].strip()
            comment = value.find(" #")
            if comment != -1:
                value = value[:comment].strip()
            return value
    raise KeyError(name)


def knobs_from_yml(path):
    block = arrivals_block(path.read_text())
    if any("preservation" in line for line in block):
        raise ValueError("arrivals 블록에 preservation 설정이 있다")
    return {
        "enabled": field(block, "enabled"),
        "daily_limit": int(field(block, "key-daily-limit")),
        "burst": int(field(block, "queue-capacity")),
        "refresh_seconds": parse_duration(field(block, "refresh-interval")),
        "validity_seconds": parse_duration(field(block, "validity")),
        "retention_seconds": parse_duration(field(block, "retention")),
        "cleanup": field(block, "cleanup-interval"),
    }


def arrival_select_columns(script_text):
    marker = 'copy_hourly arrival_snapshot "'
    start = script_text.index(marker) + len(marker)
    end = script_text.index('"', start)
    select_list = script_text[start:end].split("FROM", 1)[0]
    columns = []
    for part in select_list.split(","):
        name = part.strip().split(".")[-1]
        if name and name != "SELECT":
            columns.append(name)
    return columns


def loader_columns(java_text):
    marker = 'new Table("arrival_snapshot"'
    start = java_text.index(marker)
    region = java_text[start:java_text.index("));", start)]
    required = region.split("List.of(", 1)[1]
    required, optional = required.split("List.of(", 1)
    def names(chunk):
        return [item.strip().strip('"') for item in chunk.split(")")[0].split(",") if item.strip()]
    return names(required), names(optional)


def run_extract(env, args):
    return subprocess.run(
        ["bash", str(EXTRACT_SH), *args],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        timeout=90,
        check=False,
    )


def self_test():
    assert parse_duration("40s") == 40
    assert parse_duration("10m") == 600
    assert parse_duration("1h") == 3600
    assert parse_duration("7d") == 7 * 86400
    assert eager_calls(86400, 1, 4) == 4
    assert eager_calls(10000, 8, 3600) == 424
    assert eager_calls(86400, 8, 3600) == 3607

    kept = estimate(4, 40, 3600, 600, 0, 3876, 10000, 8, 60, 40, 600)
    assert kept["smootherCapacity"] == 424
    assert kept["requestedCalls"] == 360
    assert kept["acceptedCalls"] == 360
    assert kept["fitsInSmoother"] is True
    assert kept["rowsPresent"] == 64
    assert kept["payloadBytesPresent"] == 64 * 3876
    assert kept["userTrafficIncluded"] is False
    assert kept["intervalRaisedToRefresh"] is False

    raised = estimate(4, 20, 3600, 600, 0, 1, 10000, 8, 60, 40, 600)
    assert raised["intervalRaisedToRefresh"] is True
    assert raised["effectiveIntervalSeconds"] == 40
    assert raised["acceptedCalls"] == 360

    crowded = estimate(513, 40, 3600, 7 * 86400, 0, 1000, 10000, 8, 60, 40, 600)
    assert crowded["fitsInSmoother"] is False
    assert crowded["acceptedCalls"] == 424
    assert crowded["requestedCalls"] == 513 * 90
    assert crowded["rowsPresent"] == 424

    week = estimate(4, 40, 3600, 7 * 86400, 86400, 10, 10000, 8, 60, 40, 600)
    assert week["rowsPresent"] == 360
    assert week["retainForSeconds"] == 7 * 86400
    assert week["productRetentionSeconds"] == 600

    guide = (ROOT / "docs/salmon-action/offline-run.md").read_text()
    assert "1시간 창의 최대 호출은 424이다" in guide
    assert "창이 시작될 때 버킷이 가득 차 있고" in guide
    assert "남는 행은 64이다" in guide
    assert "preservation-inputs.example.json" in guide

    example = json.loads(EXAMPLE_JSON.read_text())
    assert example["status"] == "undecided"
    assert example["retainFor"] is None
    assert example["stationCount"] is None
    assert example["interval"] is None

    knobs = knobs_from_yml(WORKER_YML)
    assert knobs == {
        "enabled": "${ARRIVALS_ENABLED:false}",
        "daily_limit": 10000,
        "burst": 8,
        "refresh_seconds": 40,
        "validity_seconds": 60,
        "retention_seconds": 600,
        "cleanup": "60s",
    }
    cli = subprocess.run(
        [sys.executable, "-I", str(Path(__file__)), "estimate",
         "--worker-yml", str(WORKER_YML),
         "--stations", "4", "--interval", "40s", "--window", "1h",
         "--retain-for", "10m", "--extract-after", "0s", "--bytes", "3876"],
        cwd=ROOT, text=True, capture_output=True, timeout=30, check=False,
    )
    assert cli.returncode == 0, cli.stderr
    assert json.loads(cli.stdout)["rowsPresent"] == 64

    from_yml = estimate(
        4, 40, 3600, knobs["retention_seconds"], 0, 3876,
        knobs["daily_limit"], knobs["burst"], knobs["validity_seconds"], knobs["refresh_seconds"],
        knobs["retention_seconds"],
    )
    assert from_yml["rowsPresent"] == 64

    assert REFILL_LINE in RATE_LIMIT_JAVA.read_text()
    assert POLICY_LINE in RUNTIME_JAVA.read_text()

    script_text = EXTRACT_SH.read_text()
    selected = arrival_select_columns(script_text)
    arrival_sql = script_text.split('copy_hourly arrival_snapshot "', 1)[1].split('"', 1)[0]
    assert "stale_at <" not in arrival_sql
    assert "received_at >= :'from'" in arrival_sql
    assert "received_at < :'to'" in arrival_sql
    assert "provider = 'GBIS'" in arrival_sql
    required, optional = loader_columns(INPUT_FOLDER.read_text())
    assert set(selected) == set(required) | set(optional)
    assert set(required) <= set(selected)
    assert "vehicle_id" not in selected
    assert "plate_number" not in selected

    missing = Path(tempfile.mkdtemp()) / "not-created"
    blocked = run_extract(
        {**os.environ, "SALMON_EXTRACT_HOUR": "08", "SALMON_EXTRACT_LOCAL": ""},
        ["2026-11-12T01:00:00Z", "2026-11-12T02:00:00Z", str(missing), "204000057"],
    )
    assert blocked.returncode == 1
    assert "출퇴근" in blocked.stderr
    assert not missing.exists()

    bad_route = run_extract(
        {**os.environ, "SALMON_EXTRACT_LOCAL": "1"},
        ["2026-11-12T01:00:00Z", "2026-11-12T02:00:00Z", str(missing), "not-a-route"],
    )
    assert bad_route.returncode == 1
    assert "9자리" in bad_route.stderr

    with tempfile.TemporaryDirectory() as temp_name:
        temp = Path(temp_name)
        bin_dir = temp / "bin"
        bin_dir.mkdir()
        log_path = temp / "psql.log"
        fake = bin_dir / "psql"
        fake.write_text(
            "#!/bin/sh\n"
            "sql=$(cat)\n"
            "printf '%s\\n---SQL---\\n' \"$sql\" >> \"$SALMON_FAKE_PSQL_LOG\"\n"
            "case \"$sql\" in\n"
            "  *generate_series*) printf '%s\\n' 2026-11-12T01:00:00Z 2026-11-12T02:00:00Z ;;\n"
            "  *) printf '%s\\n' id ;;\n"
            "esac\n"
        )
        fake.chmod(fake.stat().st_mode | stat.S_IEXEC)
        output = temp / "out"
        completed = run_extract(
            {
                **os.environ,
                "PATH": f"{bin_dir}:/usr/bin:/bin",
                "SALMON_EXTRACT_LOCAL": "1",
                "SALMON_EXTRACT_HOUR": "08",
                "SALMON_FAKE_PSQL_LOG": str(log_path),
            },
            ["2026-11-12T01:00:00Z", "2026-11-12T02:00:00Z", str(output), "204000057"],
        )
        assert completed.returncode == 0, completed.stderr
        assert "출퇴근" not in completed.stderr
        assert not (output / "INCOMPLETE").exists()
        snapshot = output / "arrival_snapshot.csv.gz"
        assert snapshot.is_file()
        assert gzip.open(snapshot, "rt").read().strip() == "id"
        statements = log_path.read_text().split("---SQL---")
        arrival_sql = next(statement for statement in statements if "FROM arrival_snapshot" in statement)
        assert "plate_number" not in log_path.read_text()
        assert "feature_snapshot" not in log_path.read_text()
        assert "vehicle_id" not in arrival_sql

    print("self-test passed")
    return 0


def build_parser():
    parser = argparse.ArgumentParser(description="도착 스냅샷 보존 견적. 운영 설정은 바꾸지 않는다.")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--stations", type=int)
    parser.add_argument("--interval", type=parse_duration)
    parser.add_argument("--window", type=parse_duration)
    parser.add_argument("--retain-for", type=parse_duration)
    parser.add_argument("--extract-after", type=parse_duration)
    parser.add_argument("--bytes", type=int)
    parser.add_argument("--daily-limit", type=int)
    parser.add_argument("--burst", type=int)
    parser.add_argument("--validity", type=parse_duration)
    parser.add_argument("--refresh-interval", type=parse_duration)
    parser.add_argument("--worker-yml", type=Path)
    return parser


def main(argv):
    if argv and argv[0] == "estimate":
        argv = argv[1:]
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    required = {
        "stations": args.stations,
        "interval": args.interval,
        "window": args.window,
        "retain-for": args.retain_for,
        "extract-after": args.extract_after,
        "bytes": args.bytes,
    }
    missing = [name for name, value in required.items() if value is None]
    if missing:
        print("없는 인자: " + ", ".join(missing), file=sys.stderr)
        return 2
    daily_limit = args.daily_limit
    burst = args.burst
    validity = args.validity
    refresh = args.refresh_interval
    product_retention = None
    if args.worker_yml is not None:
        knobs = knobs_from_yml(args.worker_yml)
        daily_limit = knobs["daily_limit"] if daily_limit is None else daily_limit
        burst = knobs["burst"] if burst is None else burst
        validity = knobs["validity_seconds"] if validity is None else validity
        refresh = knobs["refresh_seconds"] if refresh is None else refresh
        product_retention = knobs["retention_seconds"]
    if None in (daily_limit, burst, validity, refresh, product_retention):
        print("하루 한도, burst, validity, refresh-interval, 제품 retention이 없다. --worker-yml 을 주라.", file=sys.stderr)
        return 2
    report = estimate(
        args.stations, args.interval, args.window, args.retain_for, args.extract_after, args.bytes,
        daily_limit, burst, validity, refresh, product_retention,
    )
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
