"""로컬 합성 모델 번들과 그 생성 도구의 회귀 검사. 모델 정확도 검사가 아니다."""
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location(
    "build_model_reference", ROOT / "dev/data/tools/build_model_reference.py")
tool = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tool)


class ModelReferenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.workspace = Path(self.temporary.name)

    def source(self):
        tool.build_source(tool.REFERENCE, tool.ROUTES, self.workspace / "source")
        return self.workspace / "source"

    def reports(self, edit=None):
        """저장소 번들의 증거에 기록된 두 검사의 보고서를 꺼낸다. edit로 보고서를 바꿔 볼 수 있다."""
        reports = {}
        for check in tool.PERFORMED:
            result = json.loads((tool.REFERENCE / f"evidence-{check}.json").read_bytes())["result"]
            if edit:
                edit(check, result)
            reports[check] = self.workspace / f"{check}.json"
            reports[check].write_bytes(tool.compact(result))
        return reports

    def test_저장소의_번들은_도구로_다시_만든_것과_바이트가_같다(self):
        self.assertEqual(tool.differences(tool.REFERENCE, tool.ROUTES), [])

    def test_번들에서_파일_하나가_달라지면_다시_만든_것과_다르다고_알린다(self):
        reference = self.workspace / "reference"
        reference.mkdir()
        for path in tool.REFERENCE.iterdir():
            (reference / path.name).write_bytes(path.read_bytes())
        (reference / "evidence-settlement.json").write_bytes(b"{}\n")
        manifest = json.loads((reference / "manifest.json").read_bytes())
        manifest["runtime"]["release_id"] = "another-release"
        (reference / "manifest.json").write_bytes(tool.compact(manifest))

        self.assertEqual(tool.differences(reference, tool.ROUTES), ["evidence-settlement.json", "manifest.json"])

    def test_원본은_모델_파일을_그대로_두고_통계에는_확정_입력_표시만_더한다(self):
        source = self.source()

        for name in tool.COPIED:
            self.assertEqual((source / name).read_bytes(), (tool.REFERENCE / name).read_bytes(), name)
        routes = json.loads((source / "statistics-manifest.json").read_bytes())["routes"]
        self.assertEqual(len(routes), 8)
        for route in routes:
            statistics = json.loads((source / f"statistics-{route}.json").read_bytes())
            self.assertEqual(statistics.pop("input_profile"), "alighting-confirmed-closure-v2")
            self.assertEqual(sorted(statistics), ["alighting", "events_known_through", "min_per_stop",
                                                  "observations_through", "route", "route_metadata", "tables"])
            self.assertTrue(all(table == [] for table in statistics["tables"].values()), route)
            self.assertEqual({(stop["p"], stop["kappa"], stop["r"], tuple(stop["lam"]))
                              for stop in statistics["alighting"]["stops"]}, {(0.5, 2, 3, (1,) * 8)}, route)
        manifest = json.loads((source / "manifest.json").read_bytes())
        self.assertIs(manifest["serving_ready"], False)
        self.assertIs(manifest["synthetic_fixture"], True)

    def test_원본의_노선_참조는_공개_노선_정보의_정류장과_같다(self):
        source = self.source()
        catalog = {route["displayName"]: route for route in json.loads(tool.ROUTES.read_bytes())["routes"]}

        for reference in json.loads((source / "route-reference.json").read_bytes())["routes"]:
            route = catalog[reference["modelRoute"]]
            self.assertEqual(reference["sourceRouteId"], route["routeId"])
            self.assertEqual(reference["stops"], [
                dict(order=stop["sequence"], id=stop["stationId"], boardingAllowed=stop["boardingAllowed"],
                     direction=stop["direction"]) for stop in route["stops"]])

    def test_서빙_번들은_실행한_두_검사의_영수증만_싣고_실행하지_않은_검사를_밝힌다(self):
        tool.build_serving(self.source(), self.reports(), self.workspace / "serving")
        serving = self.workspace / "serving"
        names = {path.name for path in serving.iterdir()}
        manifest = json.loads((serving / "manifest.json").read_bytes())

        self.assertEqual({name for name in names if name.startswith(("verification-", "evidence-"))}, {
            "verification-feature_parity.json", "evidence-feature_parity.json",
            "verification-runtime_validation.json", "evidence-runtime_validation.json",
            "verification-source-manifest.json"})
        self.assertEqual(names - {"manifest.json"}, set(manifest["files"]))
        self.assertIs(manifest["serving_ready"], True)
        self.assertIs(manifest["synthetic_fixture"], True)
        self.assertEqual(manifest["runtime"]["unverified_checks"], ["historical_replay", "settlement"])
        self.assertEqual(manifest["runtime"]["input_profile"], "alighting-confirmed-closure-v2")
        self.assertEqual(manifest["runtime"]["data_through"], "2026-10-21T00:00:00Z")
        self.assertEqual(manifest["runtime"]["source_manifest_sha256"],
                         tool.sha256((serving / "verification-source-manifest.json").read_bytes()))

    def test_다른_원본으로_만든_보고서나_통과하지_못한_보고서로는_서빙_번들을_만들지_않는다(self):
        source = self.source()

        def other_source(check, result):
            if check == "feature_parity":
                result["bundle_manifest_sha256"] = "0" * 64

        def failed(check, result):
            if check == "runtime_validation":
                result["passed"] = False

        for name, edit in (("other", other_source), ("failed", failed)):
            with self.subTest(name), self.assertRaises(ValueError):
                tool.build_serving(source, self.reports(edit), self.workspace / name)
            self.assertFalse((self.workspace / name).exists())

    def test_실행하지_않은_검사의_보고서를_함께_주면_서빙_번들을_만들지_않는다(self):
        reports = self.reports()
        reports["settlement"] = reports["runtime_validation"]

        with self.assertRaises(ValueError):
            tool.build_serving(self.source(), reports, self.workspace / "serving")
        self.assertFalse((self.workspace / "serving").exists())

    def test_로컬_볼륨_이름의_판은_초기_데이터_판과_같다(self):
        version = re.search(r'VERSION = "local-data-(v\d+)"',
                            (ROOT / "dev/data/java/com/gustler/localdata/ReferenceBundle.java").read_text()).group(1)
        compose = (ROOT / "compose.local.yaml").read_text()
        volumes = re.findall(r"^  ([a-z-]+-data(?:-v\d+)?):$", compose[compose.rindex("\nvolumes:"):], re.M)

        self.assertEqual(sorted(volumes), sorted(
            ["settings-data", f"postgres-data-{version}", f"model-data-{version}"]))


if __name__ == "__main__":
    unittest.main()
