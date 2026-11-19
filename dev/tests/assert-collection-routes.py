"""로컬 API의 25개 수집 노선과 예보 유무를 확인한다. 운영 부하/정확도 검사가 아니다."""
import json
import re
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

ADDED = {"204000081", "234000002", "234000313", "234000878", "234000882", "204000082",
         "234001739", "233000266", "204000170", "234000879", "228000393", "200000314"}
OBSERVATION_ONLY = {"234001476", "222000209", "227000016", "234000079", "234000015"}


EPOCH = datetime(1970, 1, 1, tzinfo=timezone.utc)
MICROSECOND = timedelta(microseconds=1)
# 보드가 기준으로 삼는 관측(배치의 응답 수신 시각)마다 그 배치의 예보 처리 착수 시각을 읽는다. 시각은 1970년부터의 마이크로초다.
CONFIRMED_AT_SQL = """
SELECT r.source_route_id, (extract(epoch FROM b.response_received_at) * 1000000)::bigint,
       (extract(epoch FROM run.confirmed_at) * 1000000)::bigint
FROM forecast_run run
JOIN observation_batch b ON b.id = run.observation_batch_id AND b.route_version_id = run.route_version_id
JOIN route r ON r.id = run.route_id
WHERE (extract(epoch FROM b.response_received_at) * 1000000)::bigint >= {since}
"""


def parse_observed_at(value):
    # Python 3.9/3.10의 fromisoformat은 5자리 소수점 등을 읽지 못한다.
    # DB가 저장하는 자릿수가 마이크로초라 그보다 작은 자릿수는 필요하지 않다.
    value = re.sub(r"(\.\d{6})\d+", r"\1", value).replace("Z", "+00:00")
    pattern = "%Y-%m-%dT%H:%M:%S" + (".%f" if "." in value else "") + "%z"
    return datetime.strptime(value, pattern)

def microseconds(at):
    return (at - EPOCH) // MICROSECOND

def supports_time(confirmed_at, holiday_dates):
    """앱이 예보 범위를 정하는 규칙이다. 기준 시각은 배치의 예보 처리 착수 시각이다(관측 수신 시각이 아니다)."""
    local = confirmed_at.astimezone(ZoneInfo("Asia/Seoul"))
    return (local.weekday() < 5 and local.date().isoformat() not in holiday_dates
            and (6 <= local.hour < 10 or 17 <= local.hour < 21))

def read_confirmed_at(query, since):
    """(노선 ID, 관측 수신 시각)마다 그 배치의 예보 처리 착수 시각을 돌려준다. 두 시각 모두 1970년부터의 마이크로초다."""
    confirmed = {}
    for line in query(CONFIRMED_AT_SQL.format(since=int(since))).splitlines():
        if line.strip():
            route_id, received, confirmed_at = line.strip().split("|")
            confirmed[(route_id, int(received))] = EPOCH + int(confirmed_at) * MICROSECOND
    return confirmed

def psql(command):
    """psql 명령 뒤에 SQL 한 문장을 붙여 실행하고 출력(-A -t 형식)을 돌려주는 함수를 만든다."""
    assert command, "로컬 DB에 붙는 psql 명령이 필요하다"
    def query(sql):
        result = subprocess.run(command + [sql], capture_output=True, text=True, timeout=30)
        assert result.returncode == 0, f"DB 조회 실패: {result.stderr.strip()}"
        return result.stdout
    return query


def verify_board(route_id, status, body, has_model, expect_forecasts=True):
    assert status == 200, f"{route_id}: board HTTP {status}, code={body.get('code')}"
    expected = "FORECAST_READY" if has_model else "PREPARING"
    assert body["route"]["id"] == route_id, f"{route_id}: 다른 노선 응답"
    assert body["route"]["status"] == expected, f"{route_id}: board status != {expected}"
    forecasts = [vehicle["forecast"] for stop in body["stops"] for vehicle in stop["approachingVehicles"]]
    assert forecasts, f"{route_id}: 확인할 접근 차량 없음"
    if has_model and expect_forecasts:
        assert any(item.get("status") == "AVAILABLE" for item in forecasts), f"{route_id}: 제공 중인 예보 없음"
    else:
        assert all(forecast == {"status": "UNAVAILABLE"} for forecast in forecasts), f"{route_id}: 지원 노선/시간 밖의 예보 노출"



def model_scope(data):
    manifest = json.loads((data / "model-reference" / "statistics-manifest.json").read_text())
    model_routes = set(manifest["routes"])
    collected = json.loads((data / "routes.json").read_text())["routes"]
    catalog = json.loads((data / "catalog-routes.json").read_text())["routes"]
    supported = {route["routeId"] for route in collected + catalog if route["displayName"] in model_routes}
    assert len(supported) == len(model_routes), "모델 지원 노선과 수집 노선 정보 불일치"
    return supported, manifest["holiday_dates"]

def verify(api, data, query):
    """query는 SQL 한 문장을 받아 로컬 DB에서 실행하고 psql -A -t 형식의 출력을 돌려준다."""
    assert api.startswith("http://127.0.0.1:") or api.startswith("http://localhost:"), "local API only"
    reference = json.loads((data / "routes.json").read_text())["routes"]
    catalog = json.loads((data / "catalog-routes.json").read_text())["routes"]
    reference_ids = {route["routeId"] for route in reference}
    supported_ids, holiday_dates = model_scope(data)

    def get(path):
        try:
            with urllib.request.urlopen(api + path, timeout=10) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    status, body = get("/api/v1/routes")
    assert status == 200, f"노선 목록 HTTP {status}"
    listed = {route["id"]: route for route in body["routes"]}
    assert set(listed) == {route["routeId"] for route in reference + catalog} - OBSERVATION_ONLY
    for route_id in sorted(OBSERVATION_ONLY):
        for endpoint in ("board", "watch", "vehicles"):
            status, body = get(f"/api/v1/routes/{route_id}/{endpoint}")
            assert status == 404 and body["code"] == "ROUTE_NOT_FOUND", f"{route_id}/{endpoint}: HTTP {status}"
    failures = []
    boards = {}
    for route in reference + catalog:
        route_id = route["routeId"]
        if route_id not in ADDED | reference_ids:
            continue
        try:
            has_model = route_id in supported_ids
            expected = "FORECAST_READY" if has_model else "PREPARING"
            assert listed[route_id]["displayName"] == route["displayName"], f"{route_id}: 노선명 불일치"
            assert listed[route_id]["status"] == expected, f"{route_id}: 목록 status != {expected}"
            status, body = get(f"/api/v1/routes/{route_id}/board")
            observed = microseconds(parse_observed_at(body["observedAt"])) if has_model and status == 200 else None
            boards[route_id] = (status, body, has_model, observed)
        except (AssertionError, KeyError, ValueError, urllib.error.URLError) as error:
            failures.append(f"{route_id}: {error}")
    # 보드를 모두 받은 뒤에 읽어야 각 보드가 기준으로 삼은 배치의 실행 줄이 빠지지 않는다.
    observed_times = [observed for _, _, _, observed in boards.values() if observed is not None]
    confirmed = read_confirmed_at(query, min(observed_times)) if observed_times else {}
    for route_id, (status, body, has_model, observed) in boards.items():
        try:
            expect_forecasts = False
            if observed is not None:
                assert (route_id, observed) in confirmed, f"{route_id}: 보드가 기준으로 삼은 관측의 예보 실행 줄 없음"
                expect_forecasts = supports_time(confirmed[(route_id, observed)], holiday_dates)
            verify_board(route_id, status, body, has_model, expect_forecasts)
        except (AssertionError, KeyError) as error:
            failures.append(f"{route_id}: {error}")
    assert not failures, "\n".join(failures)
    print(f"내부 관측 5개 비공개, 기존 20개 보드와 모델 지원 {len(supported_ids)}개 예보 확인 완료")


if __name__ == "__main__":
    # 세 번째 인자부터는 로컬 DB에 붙는 psql 명령이다. 예: docker exec <postgres 컨테이너> psql -X -q -A -t -U ... -d ... -c
    verify(sys.argv[1], Path(sys.argv[2]), psql(sys.argv[3:]))
