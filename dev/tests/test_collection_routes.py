"""Board API 계약에 대한 검사 스크립트의 회귀 검사. 응답은 만든 예시다."""
import importlib.util
from datetime import datetime
from pathlib import Path
import unittest

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

    def test_관측_시각과_공휴일로_검증된_출퇴근_범위를_판단한다(self):
        for hour, expected in [(5, False), (6, True), (9, True), (10, False),
                               (16, False), (17, True), (20, True), (21, False)]:
            with self.subTest(hour=hour):
                at = datetime.fromisoformat(f"2026-11-10T{hour:02d}:00:00+09:00")
                self.assertEqual(checks.supports_time(at, []), expected)
        self.assertFalse(checks.supports_time(datetime.fromisoformat("2026-11-14T08:00:00+09:00"), []))
        self.assertFalse(checks.supports_time(datetime.fromisoformat("2026-11-10T08:00:00+09:00"), ["2026-11-10"]))


if __name__ == "__main__":
    unittest.main()
