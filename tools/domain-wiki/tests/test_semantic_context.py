import json
import tempfile
import unittest
from pathlib import Path

from domain_wiki.semantic_context import SemanticContextError, load_semantic_context


class SemanticContextTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "semantic-context.json"
        self.context = {
            "schemaVersion": 1,
            "provider": "typescript-language-service",
            "headSha": "abc123",
            "files": [{
                "path": "src/policy.ts",
                "symbols": [{
                    "name": "withBoard",
                    "kind": "function",
                    "line": 20,
                    "references": [{"path": "src/screen.ts", "line": 45}],
                }],
            }],
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_loads_context_bound_to_reviewed_head_and_mapped_paths(self):
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        loaded = load_semantic_context(self.path, "abc123", ("src/policy.ts",))
        self.assertEqual("withBoard", loaded["files"][0]["symbols"][0]["name"])
        self.assertEqual(
            "src/screen.ts",
            loaded["files"][0]["symbols"][0]["references"][0]["path"],
        )

    def test_rejects_context_for_another_head(self):
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        with self.assertRaisesRegex(SemanticContextError, "head SHA"):
            load_semantic_context(self.path, "different", ("src/policy.ts",))


if __name__ == "__main__":
    unittest.main()
