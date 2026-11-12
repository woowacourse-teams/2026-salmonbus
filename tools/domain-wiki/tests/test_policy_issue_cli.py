import json
import tempfile
import unittest
from pathlib import Path

from domain_wiki.policy_issue_cli import _path_matches, main


class PolicyIssueCliTest(unittest.TestCase):
    def test_recursive_path_pattern_does_not_match_a_sibling_prefix(self):
        self.assertTrue(_path_matches("frontend/src/pages/view.ts", "frontend/src/pages/**"))
        self.assertFalse(_path_matches("frontend/src/pages-old/view.ts", "frontend/src/pages/**"))

    def test_missing_policy_registry_records_an_unavailable_review(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = root / "repository"
            wiki = root / "wiki"
            output = root / "output"
            repository.mkdir()
            wiki.mkdir()
            pull_request = root / "pr.json"
            pull_request.write_text(
                json.dumps({"title": "정책 변경", "body": "본문"}),
                encoding="utf-8",
            )

            main([
                "--repository", str(repository),
                "--base", "unused-base", "--head", "unused-head",
                "--target-repository", "owner/repository",
                "--source-url", "https://github.com/owner/repository/pull/1",
                "--pull-request-context", str(pull_request),
                "--wiki-directory", str(wiki),
                "--output-directory", str(output),
            ])

            result = json.loads(
                (output / "policy-result.json").read_text(encoding="utf-8")
            )
            comment = (output / "policy-comment.md").read_text(encoding="utf-8")
            self.assertEqual("policy-source-unavailable", result["status"])
            self.assertIn("정책이 아직 초기화되지 않아", comment)
            self.assertNotIn("문제가...없네..?", comment)


if __name__ == "__main__":
    unittest.main()
