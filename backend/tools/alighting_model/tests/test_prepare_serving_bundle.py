"""Packaging guards use synthetic artifacts; these are not operational PASS receipts."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("prepare_serving_bundle", Path(__file__).resolve().parents[1] / "prepare_serving_bundle.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class ServingBundleTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.source = self.root / "source"
        self.receipts = self.root / "receipts"
        self.output = self.root / "release"
        self.source.mkdir()
        self.receipts.mkdir()
        files = {}
        for route in MODULE.ROUTES:
            content = json.dumps({"events_known_through": "2026-10-21T10:00:00+09:00",
                                  "observations_through": "2026-10-21T10:02:00+09:00"}).encode()
            name = f"statistics-{route}.json"
            (self.source / name).write_bytes(content)
            files[name] = MODULE.digest(content)
        self.manifest = json.dumps({"schema": "alighting-inference-bundle-v1", "serving_ready": False,
                                    "files": files}).encode()
        (self.source / "manifest.json").write_bytes(self.manifest)
        for check in MODULE.CHECKS:
            evidence = b'{"synthetic":true}'
            name = f"evidence-{check}.json"
            (self.receipts / name).write_bytes(evidence)
            receipt = {"schema": "alighting-serving-verification-v1", "check": check, "status": "PASS",
                       "source_manifest_sha256": MODULE.digest(self.manifest),
                       "feature_contract_version": "alighting-online-v1", "verified_cases": 1,
                       "evidence_file": name, "evidence_sha256": MODULE.digest(evidence)}
            (self.receipts / f"verification-{check}.json").write_text(json.dumps(receipt))

    def tearDown(self):
        self.temp.cleanup()

    def test_retains_original_and_packages_hashed_receipts(self):
        MODULE.prepare(self.source, self.output, "alighting-test", self.receipts)
        self.assertEqual((self.source / "manifest.json").read_bytes(), self.manifest)
        release = json.loads((self.output / "manifest.json").read_text())
        self.assertTrue(release["serving_ready"])
        self.assertEqual(release["runtime"]["data_through"], "2026-10-21T10:02:00+09:00")
        for name, expected in release["files"].items():
            self.assertEqual(MODULE.digest((self.output / name).read_bytes()), expected)

    def test_historical_source_keeps_original_bytes_and_new_release_names(self):
        original = self.manifest.replace(b'alighting-inference-bundle-v1', b'les-inference-bundle-v1')
        (self.source / 'manifest.json').write_bytes(original)
        for check in MODULE.CHECKS:
            path = self.receipts / f'verification-{check}.json'
            receipt = json.loads(path.read_text())
            receipt['source_manifest_sha256'] = MODULE.digest(original)
            path.write_text(json.dumps(receipt))
        MODULE.prepare(self.source, self.output, 'alighting-confirmed-test', self.receipts)
        release = json.loads((self.output / 'manifest.json').read_text())
        self.assertEqual(release['schema'], 'alighting-inference-bundle-v1')
        self.assertEqual(release['runtime']['release_id'], 'alighting-confirmed-test')
        self.assertEqual((self.source / 'manifest.json').read_bytes(), original)
        self.assertEqual((self.output / 'verification-source-manifest.json').read_bytes(), original)

    def test_release_id_matches_database_length_limit(self):
        for length in (81, 120):
            with self.subTest(length=length), self.assertRaises(ValueError):
                MODULE.prepare(self.source, self.output, "a" * length, self.receipts)
            self.assertFalse(self.output.exists())
        MODULE.prepare(self.source, self.output, "a" * 80, self.receipts)
        self.assertEqual(len(json.loads((self.output / "manifest.json").read_text())["runtime"]["release_id"]), 80)

    def test_missing_evidence_does_not_create_release(self):
        (self.receipts / "evidence-feature_parity.json").unlink()
        with self.assertRaises(FileNotFoundError):
            MODULE.prepare(self.source, self.output, "alighting-test", self.receipts)
        self.assertFalse(self.output.exists())

    def test_failed_receipt_is_not_promoted(self):
        path = self.receipts / "verification-historical_replay.json"
        receipt = json.loads(path.read_text())
        receipt["status"] = "FAIL"
        path.write_text(json.dumps(receipt))
        with self.assertRaises(ValueError):
            MODULE.prepare(self.source, self.output, "alighting-test", self.receipts)
        self.assertFalse(self.output.exists())

    def test_changed_source_requires_new_validation(self):
        (self.source / "statistics-3330.json").write_text("{}")
        with self.assertRaises(ValueError):
            MODULE.prepare(self.source, self.output, "alighting-test", self.receipts)
        self.assertFalse(self.output.exists())

    def test_output_is_never_overwritten(self):
        MODULE.prepare(self.source, self.output, "alighting-test", self.receipts)
        with self.assertRaises(ValueError):
            MODULE.prepare(self.source, self.output, "alighting-test", self.receipts)


if __name__ == "__main__":
    unittest.main()
