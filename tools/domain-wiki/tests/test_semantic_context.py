import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from domain_wiki.semantic_context import SemanticContextError, load_semantic_context


class SemanticContextTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.path = self.root / "semantic-context.json"
        (self.root / "src").mkdir()
        (self.root / "src/policy.ts").write_text(
            ("\n" * 19) + "function withBoard() {}\n", encoding="utf-8"
        )
        (self.root / "src/screen.ts").write_text(
            ("\n" * 44) + "withBoard();\n", encoding="utf-8"
        )
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.email", "test@example.com"], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.name", "Test"], check=True)
        subprocess.run(["git", "-C", str(self.root), "add", "src"], check=True)
        subprocess.run(["git", "-C", str(self.root), "commit", "-qm", "fixture"], check=True)
        self.head = subprocess.check_output(
            ["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True
        ).strip()
        self.context = {
            "schemaVersion": 1,
            "provider": "typescript-language-service",
            "headSha": self.head,
            "files": [{
                "path": "src/policy.ts",
                "symbols": [{
                    "name": "withBoard",
                    "kind": "function",
                    "line": 20,
                    "references": [{"path": "src/screen.ts", "line": 45}],
                }],
                "callTargets": [{
                    "call": "withBoard",
                    "callLine": 20,
                    "definitionPath": "src/policy.ts",
                    "definitionLine": 20,
                    "definitionSnippet": "function withBoard() {}",
                }],
            }],
        }

    def tearDown(self):
        self.temp.cleanup()

    def test_loads_context_bound_to_reviewed_head_and_mapped_paths(self):
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        loaded = load_semantic_context(
            self.path, self.head, ("src/policy.ts",), self.root
        )
        self.assertEqual("withBoard", loaded["files"][0]["symbols"][0]["name"])
        self.assertEqual(
            "src/screen.ts",
            loaded["files"][0]["symbols"][0]["references"][0]["path"],
        )
        self.assertEqual(
            "src/policy.ts",
            loaded["files"][0]["callTargets"][0]["definitionPath"],
        )

    def test_rejects_context_for_another_head(self):
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        with self.assertRaisesRegex(SemanticContextError, "head SHA"):
            load_semantic_context(
                self.path, "different", ("src/policy.ts",), self.root
            )

    def test_rejects_symbol_or_reference_not_present_at_reported_line(self):
        self.context["files"][0]["symbols"][0]["line"] = 19
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        with self.assertRaisesRegex(SemanticContextError, "symbol does not match"):
            load_semantic_context(
                self.path, self.head, ("src/policy.ts",), self.root
            )

        self.context["files"][0]["symbols"][0]["line"] = 20
        self.context["files"][0]["symbols"][0]["references"][0]["line"] = 44
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        with self.assertRaisesRegex(SemanticContextError, "reference does not match"):
            load_semantic_context(
                self.path, self.head, ("src/policy.ts",), self.root
            )

    def test_drops_definition_snippet_not_present_at_reported_line(self):
        self.context["files"][0]["callTargets"][0]["definitionSnippet"] = "fake"
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        loaded = load_semantic_context(
            self.path, self.head, ("src/policy.ts",), self.root
        )
        self.assertEqual([], loaded["files"][0]["callTargets"])

    def test_drops_call_not_present_at_reported_line(self):
        self.context["files"][0]["callTargets"][0]["call"] = "missingCall"
        self.path.write_text(json.dumps(self.context), encoding="utf-8")
        loaded = load_semantic_context(
            self.path, self.head, ("src/policy.ts",), self.root
        )
        self.assertEqual([], loaded["files"][0]["callTargets"])


if __name__ == "__main__":
    unittest.main()
