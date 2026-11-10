import argparse
import csv
import gzip
import json
import sys
from collections import defaultdict
from datetime import datetime, timedelta

PAIR_GAP_LIMIT = timedelta(seconds=120)
SLACK = timedelta(seconds=60)


def parse_time(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def remaining(prediction):
    seconds = prediction.get("sourceRemainingSeconds")
    if seconds is not None:
        return timedelta(seconds=seconds)
    return timedelta(minutes=prediction["sourceRemainingMinutes"])


def series(snapshots):
    grouped = defaultdict(list)
    for snapshot in snapshots:
        received = parse_time(snapshot["receivedAt"])
        for route in snapshot["routes"]:
            key = (snapshot["stationId"], route["routeId"], route["sourceStopOrder"])
            predictions = [
                {
                    "vehicleId": item.get("vehicleId") or None,
                    "ordinal": item["ordinal"],
                    "at": received + remaining(item),
                    "seconds": item.get("sourceRemainingSeconds") is not None,
                }
                for item in route["arrivals"]
            ]
            grouped[key].append((received, predictions))
    for entries in grouped.values():
        entries.sort(key=lambda entry: entry[0])
    return grouped


def count_basic(grouped):
    total = ordered = unordered = empty_vehicle = seconds = minutes = 0
    for entries in grouped.values():
        for _, predictions in entries:
            by_ordinal = sorted(predictions, key=lambda item: item["ordinal"])
            for item in by_ordinal:
                total += 1
                empty_vehicle += item["vehicleId"] is None
                seconds += item["seconds"]
                minutes += not item["seconds"]
            if len(by_ordinal) == 2:
                if by_ordinal[0]["at"] <= by_ordinal[1]["at"]:
                    ordered += 1
                else:
                    unordered += 1
    return {
        "predictions": total,
        "pairsOrdered": ordered,
        "pairsOutOfOrder": unordered,
        "emptyVehicleId": empty_vehicle,
        "secondsResolution": seconds,
        "minutesResolution": minutes,
    }


def count_hidden(grouped, window):
    eligible = hidden = 0
    for entries in grouped.values():
        for (first_at, first), (second_at, second) in zip(entries, entries[1:]):
            if second_at - first_at > PAIR_GAP_LIMIT or len(first) != 2:
                continue
            horizon = first_at + window
            last = max(item["at"] for item in first)
            if last > horizon:
                continue
            eligible += 1
            known = {item["vehicleId"] for item in first if item["vehicleId"]}
            earlier = [
                item for item in second
                if item["vehicleId"] and item["vehicleId"] not in known and item["at"] < last - SLACK
            ]
            hidden += bool(earlier)
    return {"eligibleWhenSecondInsideWindow": eligible, "hiddenVehicleSeen": hidden}


def count_rule_violations(grouped, window):
    eligible = violated = 0
    for entries in grouped.values():
        for (first_at, first), (second_at, second) in zip(entries, entries[1:]):
            if second_at - first_at > PAIR_GAP_LIMIT or len(first) != 2:
                continue
            horizon = first_at + window
            if max(item["at"] for item in first) <= horizon:
                continue
            eligible += 1
            known = {item["vehicleId"] for item in first if item["vehicleId"]}
            inside = [
                item for item in second
                if item["vehicleId"] and item["vehicleId"] not in known and item["at"] <= horizon - SLACK
            ]
            violated += bool(inside)
    return {"eligibleWhenSecondBeyondWindow": eligible, "ruleViolated": violated}


def analyze(snapshots, window_minutes):
    grouped = series(snapshots)
    window = timedelta(minutes=window_minutes)
    return {
        "windowMinutes": window_minutes,
        "basic": count_basic(grouped),
        "hidden": count_hidden(grouped, window),
        "coverageRule": count_rule_violations(grouped, window),
    }


def load_csv(path):
    opener = gzip.open if path.endswith(".gz") else open
    grouped = {}
    with opener(path, "rt", encoding="utf-8", newline="") as source:
        for row in csv.reader(source):
            received, _, station, route, _, order, ordinal, vehicle, _, seconds, minutes = row
            snapshot_key = (received, station)
            routes = grouped.setdefault(snapshot_key, {})
            arrivals = routes.setdefault((route, int(order)), [])
            arrivals.append({
                "vehicleId": vehicle,
                "ordinal": int(ordinal),
                "sourceRemainingSeconds": int(seconds) if seconds else None,
                "sourceRemainingMinutes": int(minutes) if minutes else None,
            })
    return [
        {
            "stationId": station,
            "receivedAt": received.replace(" ", "T"),
            "routes": [
                {"routeId": route, "sourceStopOrder": order, "arrivals": arrivals}
                for (route, order), arrivals in routes.items()
            ],
        }
        for (received, station), routes in grouped.items()
    ]


def load_snapshot_csv(path):
    """오프라인 실행기 입력 폴더의 arrival_snapshot.csv(.gz). 첫 줄이 열 이름이고 payload 열이 스냅샷 JSON이다."""
    csv.field_size_limit(32 * 2 ** 20)
    opener = gzip.open if path.endswith(".gz") else open
    with opener(path, "rt", encoding="utf-8", newline="") as source:
        return [
            {
                "stationId": row["station_id"],
                # payload 안의 receivedAt 표기에 기대지 않고 테이블의 수신 시각 열을 쓴다.
                "receivedAt": row["received_at"].replace(" ", "T"),
                "routes": json.loads(row["payload"])["routes"],
            }
            for row in csv.DictReader(source)
        ]


def snapshot(received, predictions):
    return {
        "stationId": "000000001",
        "receivedAt": received,
        "routes": [{"routeId": "100000001", "sourceStopOrder": 3, "arrivals": predictions}],
    }


def self_test():
    inside = [
        snapshot("2026-11-08T09:00:00+00:00", [
            {"vehicleId": "A", "ordinal": 1, "sourceRemainingSeconds": 300},
            {"vehicleId": "B", "ordinal": 2, "sourceRemainingSeconds": 600},
        ]),
        snapshot("2026-11-08T09:01:00+00:00", [
            {"vehicleId": "C", "ordinal": 1, "sourceRemainingSeconds": 120},
            {"vehicleId": "A", "ordinal": 2, "sourceRemainingSeconds": 240},
        ]),
    ]
    result = analyze(inside, 15)
    assert result["hidden"] == {"eligibleWhenSecondInsideWindow": 1, "hiddenVehicleSeen": 1}, result
    assert result["coverageRule"]["eligibleWhenSecondBeyondWindow"] == 0, result
    assert result["basic"]["pairsOrdered"] == 2, result

    beyond = [
        snapshot("2026-11-08T09:00:00+00:00", [
            {"vehicleId": "A", "ordinal": 1, "sourceRemainingMinutes": 5},
            {"vehicleId": "B", "ordinal": 2, "sourceRemainingMinutes": 20},
        ]),
        snapshot("2026-11-08T09:01:00+00:00", [
            {"vehicleId": "A", "ordinal": 1, "sourceRemainingMinutes": 4},
            {"vehicleId": "B", "ordinal": 2, "sourceRemainingMinutes": 19},
        ]),
    ]
    result = analyze(beyond, 15)
    assert result["coverageRule"] == {"eligibleWhenSecondBeyondWindow": 1, "ruleViolated": 0}, result
    assert result["basic"]["minutesResolution"] == 4, result

    violated = [
        beyond[0],
        snapshot("2026-11-08T09:01:00+00:00", [
            {"vehicleId": "D", "ordinal": 1, "sourceRemainingMinutes": 8},
            {"vehicleId": "A", "ordinal": 2, "sourceRemainingMinutes": 4},
        ]),
    ]
    assert analyze(violated, 15)["coverageRule"]["ruleViolated"] == 1
    empty_vehicle = [snapshot("2026-11-08T09:00:00+00:00", [
        {"vehicleId": "", "ordinal": 1, "sourceRemainingSeconds": 60}])]
    assert analyze(empty_vehicle, 15)["basic"]["emptyVehicleId"] == 1
    import os
    import tempfile
    rows = [
        "2026-11-08 09:00:00+00,q,000000001,100000001,9300,3,1,A,2,300,,",
        "2026-11-08 09:00:00+00,q,000000001,100000001,9300,3,2,B,5,,10,",
        "2026-11-08 09:01:00+00,q,000000001,100000001,9300,3,1,C,1,120,,",
        "2026-11-08 09:01:00+00,q,000000001,100000001,9300,3,2,A,2,240,,",
    ]
    rows = [row.rsplit(",", 1)[0] for row in rows]
    with tempfile.TemporaryDirectory() as folder:
        path = os.path.join(folder, "sample.csv")
        with open(path, "w", encoding="utf-8") as sample:
            sample.write("\n".join(rows) + "\n")
        loaded = load_csv(path)
    assert len(loaded) == 2, loaded
    assert analyze(loaded, 15)["hidden"]["hiddenVehicleSeen"] == 1
    with tempfile.TemporaryDirectory() as folder:
        path = os.path.join(folder, "arrival_snapshot.csv")
        with open(path, "w", encoding="utf-8", newline="") as sample:
            writer = csv.writer(sample)
            writer.writerow(["id", "station_id", "received_at", "payload"])
            for index, item in enumerate(inside):
                received = item["receivedAt"].replace("T", " ").replace("+00:00", "+00")
                writer.writerow([index, item["stationId"], received, json.dumps({"routes": item["routes"]})])
        extracted = load_snapshot_csv(path)
    assert analyze(extracted, 15) == analyze(inside, 15), extracted
    print("self-test passed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("input", nargs="?", help="JSON lines of arrival snapshots, or a headerless CSV from eta_extract_arrivals.sh")
    parser.add_argument("--csv", action="store_true")
    parser.add_argument("--snapshot-csv", action="store_true",
                        help="input is arrival_snapshot.csv(.gz) from extract_offline_input.sh")
    parser.add_argument("--window-minutes", type=float, default=15)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.input:
        parser.error("input is required unless --self-test is given")
    if args.snapshot_csv:
        snapshots = load_snapshot_csv(args.input)
    elif args.csv:
        snapshots = load_csv(args.input)
    else:
        with open(args.input, encoding="utf-8") as source:
            snapshots = [json.loads(line) for line in source if line.strip()]
    json.dump(analyze(snapshots, args.window_minutes), sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
