"""Board API 계약에 대한 검사 스크립트의 회귀 검사. 응답은 만든 예시다."""
import importlib.util
import io
import json
from datetime import datetime
from pathlib import Path
import sys
import unittest
from urllib.error import HTTPError
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("collection_routes", Path(__file__).with_name("assert-collection-routes.py"))
checks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checks)


class CollectionRoutesTest(unittest.TestCase):
    def body(self, route_id, status="PREPARING", forecast=None):
        return {"route": {"id": route_id, "status": status}, "stops": [{"approachingVehicles": [
            {"forecast": forecast if forecast is not None else {"status": "AVAILABLE" if status == "FORECAST_READY" else "UNAVAILABLE"}}]}]}

    def test_추가_12개_노선의_예보없음은_200과_UNAVAILABLE로_확인한다(self):
        for route_id in checks.ADDED:
            with self.subTest(route=route_id):
                checks.verify_board(route_id, 200, self.body(route_id), False)

    def test_새_번들을_적용한_추가_12개는_FORECAST_READY를_확인한다(self):
        for route_id in checks.ADDED:
            with self.subTest(route=route_id):
                checks.verify_board(route_id, 200, self.body(route_id, "FORECAST_READY"), True)
                with self.assertRaises(AssertionError):
                    checks.verify_board(route_id, 200, self.body(route_id), True)

    def test_503을_정상_관측_응답으로_통과시키지_않는다(self):
        with self.assertRaises(AssertionError):
            checks.verify_board("234000879", 503, {"code": "MODEL_OUT_OF_SCOPE"}, False)

    def test_미지원_노선의_예보_값_노출을_거절한다(self):
        with self.assertRaises(AssertionError):
            checks.verify_board("234000879", 200, self.body("234000879", forecast={"status": "AVAILABLE"}), False)

    def test_접근_차량이_없는_응답을_검증_완료로_처리하지_않는다(self):
        body = self.body("234000879")
        body["stops"][0]["approachingVehicles"] = []
        with self.assertRaises(AssertionError):
            checks.verify_board("234000879", 200, body, False)

    def test_기존_노선은_FORECAST_READY를_확인한다(self):
        checks.verify_board("204000057", 200, self.body("204000057", "FORECAST_READY"), True)
        with self.assertRaises(AssertionError):
            checks.verify_board("204000057", 200, self.body("204000057"), True)

    def test_지원_노선도_지원_시간_밖에는_예보없음을_확인한다(self):
        body = self.body("204000057", "FORECAST_READY", {"status": "UNAVAILABLE"})
        checks.verify_board("204000057", 200, body, True, expect_forecasts=False)
        with self.assertRaises(AssertionError):
            checks.verify_board("204000057", 200, self.body("204000057", "FORECAST_READY"),
                                True, expect_forecasts=False)

    def test_착수_시각과_공휴일로_검증된_출퇴근_범위를_판단한다(self):
        for hour, expected in [(5, False), (6, True), (9, True), (10, False),
                               (16, False), (17, True), (20, True), (21, False)]:
            with self.subTest(hour=hour):
                at = datetime.fromisoformat(f"2026-11-10T{hour:02d}:00:00+09:00")
                self.assertEqual(checks.supports_time(at, []), expected)
        self.assertFalse(checks.supports_time(datetime.fromisoformat("2026-11-14T08:00:00+09:00"), []))
        self.assertFalse(checks.supports_time(datetime.fromisoformat("2026-11-10T08:00:00+09:00"), ["2026-11-10"]))

    def test_실제_개발_번들과_수집_파일에서_지원_노선을_읽는다(self):
        supported, holidays = checks.model_scope(Path(__file__).resolve().parents[1] / "data")
        self.assertEqual(len(supported), 8)
        self.assertIn("204000057", supported)
        self.assertTrue(checks.ADDED.isdisjoint(supported))
        self.assertIsInstance(holidays, list)

    def test_CI에서_실패한_시각과_ISO_소수점_길이를_읽는다(self):
        for fraction in ["", ".1", ".12", ".123", ".1234", ".69585", ".123456", ".123456789"]:
            for zone in ["+09:00", "Z"]:
                with self.subTest(fraction=fraction, zone=zone):
                    parsed = checks.parse_observed_at("2026-11-10T22:09:01" + fraction + zone)
                    self.assertEqual(parsed.second, 1)
                    self.assertIsNotNone(parsed.utcoffset())
        self.assertEqual(checks.parse_observed_at("2026-11-10T22:09:01.69585+09:00").microsecond, 695850)
        with self.assertRaises(ValueError):
            checks.parse_observed_at("not-a-date")

    def verify_with(self, observed_at, confirmed_at, forecasts_available):
        """합성 API 응답과 합성 실행 줄로 전체 검사를 돌리고, 보드를 조회한 노선과 DB에 보낸 조회문을 돌려준다.

        confirmed_at이 None이면 보드가 기준으로 삼은 관측의 실행 줄이 DB에 없는 상황이다.
        """
        data = Path(__file__).resolve().parents[1] / "data"
        supported, _ = checks.model_scope(data)
        routes = json.loads((data / "routes.json").read_text())["routes"]
        routes += json.loads((data / "catalog-routes.json").read_text())["routes"]
        seen = []
        queries = []
        def reply(url, timeout):
            if url.endswith("/api/v1/routes"):
                payload = {"routes": [{"id": r["routeId"], "displayName": r["displayName"],
                    "status": "FORECAST_READY" if r["routeId"] in supported else "PREPARING"} for r in routes
                    if r["routeId"] not in checks.OBSERVATION_ONLY]}
            else:
                route_id, endpoint = url.split("/")[-2:]
                seen.append((route_id, endpoint))
                if route_id in checks.OBSERVATION_ONLY:
                    body = io.BytesIO(b'{"code":"ROUTE_NOT_FOUND"}')
                    raise HTTPError(url, 404, "Not found", None, body)
                has_model = route_id in supported
                forecast = {"status": "AVAILABLE" if has_model and forecasts_available else "UNAVAILABLE"}
                payload = self.body(route_id, "FORECAST_READY" if has_model else "PREPARING", forecast)
                payload["observedAt"] = observed_at
            response = io.BytesIO(json.dumps(payload).encode())
            response.status = 200
            return response
        def query(sql):
            queries.append(sql)
            if confirmed_at is None:
                return ""
            received = checks.microseconds(checks.parse_observed_at(observed_at))
            confirmed = checks.microseconds(checks.parse_observed_at(confirmed_at))
            # 다른 관측의 실행 줄이 섞여 있어도 보드가 기준으로 삼은 관측의 줄만 쓴다.
            return "".join(f"{route_id}|{received}|{confirmed}\n{route_id}|{received + 1}|0\n"
                           for route_id in sorted(supported))
        with patch.object(checks.urllib.request, "urlopen", side_effect=reply):
            checks.verify("http://127.0.0.1:1", data, query)
        return seen, queries

    def test_노선_파일로_기존_20개_보드와_비공개_5개_직접_링크를_검사한다(self):
        for hour, forecasts_available in [(8, True), (22, False)]:
            with self.subTest(hour=hour):
                observed_at = f"2026-11-10T{hour:02d}:09:01.69585+09:00"
                seen, queries = self.verify_with(observed_at, f"2026-11-10T{hour:02d}:09:03+09:00",
                                                 forecasts_available)
                self.assertEqual(sum(route not in checks.OBSERVATION_ONLY for route, _ in seen), 20)
                self.assertEqual(
                    {(route, endpoint) for route, endpoint in seen if route in checks.OBSERVATION_ONLY},
                    {(route, endpoint) for route in checks.OBSERVATION_ONLY
                     for endpoint in ("board", "watch", "vehicles")})
                self.assertEqual(len(queries), 1)
                self.assertIn(f">= {checks.microseconds(checks.parse_observed_at(observed_at))}", queries[0])

    def test_예보_범위는_관측_시각이_아니라_예보_처리_착수_시각으로_판단한다(self):
        # 범위가 갈리는 06·10·17·21시를 사이에 두고 관측을 받은 시각과 예보 처리를 시작한 시각이 다른 쪽에 놓인 경우다.
        for observed_at, confirmed_at, in_scope in [
                ("2026-11-10T05:59:58+09:00", "2026-11-10T06:00:01+09:00", True),
                ("2026-11-10T09:59:50+09:00", "2026-11-10T10:00:03+09:00", False),
                ("2026-11-10T16:59:59.999999+09:00", "2026-11-10T17:00:00+09:00", True),
                ("2026-11-10T20:59:30+09:00", "2026-11-10T21:00:00+09:00", False),
                # 금요일 밤과 토요일 새벽, 일요일 밤과 월요일 새벽은 모두 범위 밖이다. 날짜가 넘어가도 판단이 같다.
                ("2026-11-13T23:59:59+09:00", "2026-11-14T00:00:01+09:00", False),
                ("2026-11-15T23:59:59+09:00", "2026-11-16T00:00:01+09:00", False)]:
            with self.subTest(observed_at=observed_at):
                self.verify_with(observed_at, confirmed_at, forecasts_available=in_scope)
                with self.assertRaises(AssertionError):
                    self.verify_with(observed_at, confirmed_at, forecasts_available=not in_scope)

    def test_보드가_기준으로_삼은_관측의_예보_실행_줄이_없으면_실패한다(self):
        for forecasts_available in [True, False]:
            with self.subTest(forecasts_available=forecasts_available):
                with self.assertRaisesRegex(AssertionError, "예보 실행 줄 없음"):
                    self.verify_with("2026-11-10T08:09:01+09:00", None, forecasts_available)

    def test_psql_출력에서_노선과_관측_수신_시각별_착수_시각을_읽는다(self):
        confirmed = checks.read_confirmed_at(
            lambda sql: "204000057|1762729741695850|1762729743000001\n\n234000050|1762729741695850|1762729800000000\n",
            1762729741695850)
        self.assertEqual(confirmed, {
            ("204000057", 1762729741695850): checks.parse_observed_at("2025-11-09T23:09:03.000001Z"),
            ("234000050", 1762729741695850): checks.parse_observed_at("2025-11-09T23:10:00Z")})

    def test_psql_명령_뒤에_SQL을_붙여_실행하고_실패하면_오류_글과_함께_멈춘다(self):
        self.assertEqual(checks.psql([sys.executable, "-c", "import sys; print(sys.argv[1])"])("SELECT 1"), "SELECT 1\n")
        with self.assertRaisesRegex(AssertionError, "DB 조회 실패: boom"):
            checks.psql([sys.executable, "-c", "import sys; sys.exit('boom')"])("SELECT 1")
        with self.assertRaises(AssertionError):
            checks.psql([])


if __name__ == "__main__":
    unittest.main()
