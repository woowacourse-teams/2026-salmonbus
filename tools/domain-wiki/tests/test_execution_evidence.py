import json
import tempfile
import unittest
from pathlib import Path

from domain_wiki.execution_evidence import (
    ExecutionEvidenceError,
    load_execution_evidence,
)


class ExecutionEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "execution.json"
        self.payload = {
            "schemaVersion": 1,
            "headSha": "abc123",
            "checks": [{
                "id": "policy-regression",
                "command": ["npm", "test"],
                "exitCode": 0,
                "outputSha256": "a" * 64,
            }],
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_loads_successful_check_bound_to_reviewed_commit(self):
        self.path.write_text(json.dumps(self.payload), encoding="utf-8")
        loaded = load_execution_evidence(self.path, "abc123")
        self.assertEqual("policy-regression", loaded[0]["id"])

    def test_rejects_failed_or_other_commit_check(self):
        self.payload["checks"][0]["exitCode"] = 1
        self.path.write_text(json.dumps(self.payload), encoding="utf-8")
        with self.assertRaisesRegex(ExecutionEvidenceError, "invalid"):
            load_execution_evidence(self.path, "abc123")

        self.payload["checks"][0]["exitCode"] = 0
        self.path.write_text(json.dumps(self.payload), encoding="utf-8")
        with self.assertRaisesRegex(ExecutionEvidenceError, "head SHA"):
            load_execution_evidence(self.path, "different")


if __name__ == "__main__":
    unittest.main()
