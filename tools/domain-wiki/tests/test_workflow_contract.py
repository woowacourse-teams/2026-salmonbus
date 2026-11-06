import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]


class WorkflowContractTest(unittest.TestCase):
    def test_policy_review_survives_optional_context_failure(self):
        workflow = (ROOT / ".github/workflows/domain-policy-review.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("if: ${{ !cancelled() }}", workflow)
        self.assertIn("needs.semantic-context.result == 'success'", workflow)

    def test_policy_comment_is_selected_by_marker(self):
        workflow = (ROOT / ".github/workflows/domain-policy-review.yml").read_text(
            encoding="utf-8"
        )

        self.assertIn("<!-- salmonbus-policy-issue-review -->", workflow)
        self.assertIn("issues/comments/${comment_id}", workflow)
        self.assertNotIn("--edit-last", workflow)

    def test_wiki_review_and_apply_have_separate_permissions_and_concurrency(self):
        workflow = (ROOT / ".github/workflows/domain-wiki-update.yml").read_text(
            encoding="utf-8"
        )

        review = workflow.split("  review:\n", 1)[1].split("\n  approval:\n", 1)[0]
        approval = workflow.split("\n  approval:\n", 1)[1].split("\n  apply:\n", 1)[0]
        apply = workflow.split("\n  apply:\n", 1)[1]
        self.assertIn("contents: read", review)
        self.assertNotIn("contents: write", review)
        self.assertIn("environment:", approval)
        self.assertNotIn("concurrency:", approval)
        self.assertIn("contents: write", apply)
        self.assertIn("group: domain-wiki-apply", apply)
        self.assertNotIn("environment:", apply)


if __name__ == "__main__":
    unittest.main()
