import json
import unittest
from pathlib import Path


DASHBOARD = Path(__file__).with_name("grafana-user-monitoring-panels.json")


class ApplicationMetricsDashboardTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dashboard = json.loads(DASHBOARD.read_text(encoding="utf-8"))
        cls.panels = {panel["title"]: panel for panel in cls.dashboard["panels"]}

    def test_panel_set_targets_existing_operations_dashboard(self):
        self.assertEqual("salmonbus-prod", self.dashboard["targetDashboard"]["uid"])
        self.assertEqual(
            "Salmonbus 운영 | 사용자 상태와 DB 원인",
            self.dashboard["targetDashboard"]["title"],
        )
        self.assertNotIn("uid", self.dashboard)

    def test_panel_ids_are_unique(self):
        ids = [panel["id"] for panel in self.dashboard["panels"]]
        self.assertEqual(len(ids), len(set(ids)))

    def test_rds_credit_panels_are_changed_to_time_series(self):
        updates = {
            update["matchTitle"]: update for update in self.dashboard["panelUpdates"]
        }

        self.assertEqual("timeseries", updates["EBS IOPS 크레딧 잔량"]["type"])
        self.assertEqual("timeseries", updates["EBS 처리량 크레딧 잔량"]["type"])

    def test_user_monitoring_panels_use_bounded_metrics(self):
        expected = {
            "현재 회원 수": "salmonbus_users_current",
            "회원 수 표본 경과 시간": "salmonbus_users_snapshot_timestamp",
            "가입 성공, 최근 24시간": "salmonbus_user_registration_total",
            "가입 실패, 최근 24시간": "salmonbus_user_registration_total",
            "가입 결과": "salmonbus_user_registration_total",
            "로그인 결과": "salmonbus_user_login_total",
        }

        for title, metric in expected.items():
            expressions = [target["expr"] for target in self.panels[title]["targets"]]
            self.assertTrue(any(metric in expression for expression in expressions), title)

    def test_member_snapshot_panels_drop_uninitialized_samples(self):
        current = self.panels["현재 회원 수"]["targets"][0]["expr"]
        updated_at = self.panels["회원 수 표본 경과 시간"]["targets"][0]["expr"]

        self.assertIn('salmonbus_users_current{job="salmonbus/api"} >= 0', current)
        self.assertIn('salmonbus_users_snapshot_timestamp{job="salmonbus/api"} > 0', updated_at)

    def test_counter_trend_legends_do_not_sum_overlapping_windows(self):
        for title in ("가입 결과", "로그인 결과"):
            calculations = self.panels[title]["options"]["legend"]["calcs"]
            self.assertEqual([], calculations, title)

    def test_http_flow_panels_use_route_templates(self):
        favorite = self.panels["즐겨찾기 API 요청과 오류"]["targets"][0]["expr"]
        core = self.panels["핵심 board 및 watch API 오류"]["targets"][0]["expr"]

        self.assertIn('uri=~"/api/me/favorite.*"', favorite)
        self.assertIn('/api/v1/routes/{routeId}/(board|watch)', core)
        self.assertNotIn(r"\{routeId\}", core)
        self.assertIn('status=~"4..|5.."', core)

    def test_prometheus_queries_do_not_use_identifier_labels(self):
        expressions = [
            target["expr"]
            for panel in self.dashboard["panels"]
            for target in panel.get("targets", [])
        ]
        forbidden = ("user_id", "member_id", "email", "token", "group_name", "client_ip")

        for expression in expressions:
            for label in forbidden:
                self.assertNotIn(label, expression)


if __name__ == "__main__":
    unittest.main()
