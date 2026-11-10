"""홈 지도에 표시할 지원 노선 형상과 정류장 데이터를 수집한다."""

import concurrent.futures
import datetime as dt
import json
import math
import os
from pathlib import Path
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
KST = dt.timezone(dt.timedelta(hours=9))
COLLECT_INTERVAL = 6 * 60 * 60


def read_url(url):
    request = urllib.request.Request(url, headers={"User-Agent": "Salmonbus-HomeMap/1.0"})
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.read()


def coordinate(x, y):
    try:
        point = [float(x), float(y)]
    except (TypeError, ValueError):
        return None
    return point if 124 <= point[0] <= 132 and 33 <= point[1] <= 39 else None


def overview_path(raw_points):
    points = [coordinate(point.get("x"), point.get("y")) for point in raw_points]
    if len(points) < 2 or any(point is None for point in points):
        return []
    longitude_scale = 111_195 * math.cos(math.radians(points[0][1]))
    meters = [(x * longitude_scale, y * 111_195) for x, y in points]
    keep = {0, len(points) - 1}
    intervals = [(0, len(points) - 1)]
    # 도로 모양을 유지하면서 4m 이내의 중간 좌표를 줄여 홈 응답을 가볍게 한다.
    while intervals:
        first, last = intervals.pop()
        ax, ay = meters[first]
        dx, dy = meters[last][0] - ax, meters[last][1] - ay
        length_squared = dx * dx + dy * dy
        farthest, threshold = None, 16
        for index in range(first + 1, last):
            x, y = meters[index][0] - ax, meters[index][1] - ay
            ratio = min(1, max(0, (x * dx + y * dy) / length_squared)) if length_squared else 0
            deviation = (x - ratio * dx) ** 2 + (y - ratio * dy) ** 2
            if deviation > threshold:
                farthest, threshold = index, deviation
        if farthest is not None:
            keep.add(farthest)
            intervals.extend(((first, farthest), (farthest, last)))
    return [[round(value, 5) for value in points[index]] for index in sorted(keep)]


def overview_paths(geometry):
    if geometry.get("status") == "UNAVAILABLE":
        return []
    directions = geometry.get("paths") or {}
    paths = [overview_path(directions.get(direction, {}).get("points") or []) for direction in ("UP", "DOWN")]
    if all(paths):
        return paths
    # 방향별 표시 형상이 없는 노선은 판정보드와 같이 전체 도로 형상을 사용한다.
    full_path = overview_path(geometry.get("points") or [])
    return [path for path in [*paths, full_path] if path]


def supported_network():
    origin = os.environ.get("HOME_ROUTES_ORIGIN", "https://www.salmonbus.com").rstrip("/")
    routes = [route for route in json.loads(read_url(origin + "/api/v1/routes"))["routes"]
              if route.get("status") == "FORECAST_READY"]

    def load(route):
        geometry = json.loads(read_url(origin + "/api/v2/routes/" + route["id"] + "/geometry"))
        if geometry.get("routeId") != route["id"]:
            raise ValueError("노선 형상 ID 불일치")
        stops = [dict(routeId=route["id"], routeName=route["displayName"], stopId=stop["stationId"],
                     name=stop["name"], direction=stop["direction"],
                     terminalName=route["endStopName"] if stop["direction"] == "UP" else route["startStopName"],
                     point=[stop["point"]["x"], stop["point"]["y"]])
                for stop in geometry["busStops"] if stop["boardingAllowed"] and stop.get("point")]
        return stops, overview_paths(geometry)

    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        network = list(pool.map(load, routes))
    return [stop for stops, _ in network for stop in stops], [path for _, paths in network for path in paths]


def write_json(path, payload):
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")))
    temporary.replace(path)


def home_stops(stops):
    grouped = {}
    for stop in stops:
        entry = grouped.setdefault(stop["stopId"], {
            "id": stop["stopId"], "name": stop["name"], "point": stop["point"], "routes": {},
        })
        entry["routes"][(stop["routeId"], stop["direction"])] = {
            key: stop[key] for key in ("routeId", "routeName", "direction", "terminalName")
        }
    return [{**entry, "routes": list(entry["routes"].values())} for entry in grouped.values()]


def collect():
    if os.environ.get("IGNORE_ENV_FILES") == "true":
        print("지도 데이터 수집 생략: 테스트 설정입니다.")
        return
    env = ROOT / "env/.env.home.local"
    if env.exists():
        for line in env.read_text().splitlines():
            name, separator, value = line.partition("=")
            if separator and name.strip() == "HOME_ROUTES_ORIGIN":
                os.environ.setdefault("HOME_ROUTES_ORIGIN", value.strip().strip("\"'"))
    stops, route_paths = supported_network()
    if not route_paths:
        raise ValueError("게시할 지원 노선 형상이 없습니다.")
    output = ROOT / "public/home-events.json"
    write_json(output, {
        "generatedAt": dt.datetime.now(KST).isoformat(),
        "events": [],
        "stops": home_stops(stops),
        "routePaths": route_paths,
    })
    print(f"지도 데이터 수집 완료: 노선 형상 {len(route_paths)}개, {output.relative_to(ROOT)}")


def self_check():
    assert coordinate("127.1", "37.5") == [127.1, 37.5] and coordinate("NaN", "37.5") is None
    road = [{"x": 127.1, "y": 37.5}, {"x": 127.10001, "y": 37.5}, {"x": 127.101, "y": 37.5}, {"x": 127.101, "y": 37.501}]
    assert overview_path(road) == [[127.1, 37.5], [127.101, 37.5], [127.101, 37.501]]
    corrected = [road[0], {"x": 127.1005, "y": 37.501}, road[-1]]
    geometry = {
        "status": "READY",
        "points": road,
        "paths": {"UP": {"points": corrected}, "DOWN": {"points": corrected[::-1]}},
    }
    assert overview_paths(geometry) == [overview_path(corrected), overview_path(corrected[::-1])]
    assert overview_paths({**geometry, "paths": None}) == [overview_path(road)]
    assert overview_paths({**geometry, "status": "UNAVAILABLE"}) == []
    print("정류장 좌표 검증과 노선 형상 선택 검증 통과")


if __name__ == "__main__":
    watching = "--watch" in sys.argv
    while True:
        try:
            self_check() if "--self-check" in sys.argv else collect()
        except Exception as error:
            print(f"지도 데이터 수집 실패 ({type(error).__name__}): 기존 데이터는 유지합니다.", file=sys.stderr, flush=True)
            if not watching:
                sys.exit(1)
        if not watching:
            break
        time.sleep(COLLECT_INTERVAL)
