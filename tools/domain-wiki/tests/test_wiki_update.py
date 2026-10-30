import tempfile
import unittest
from pathlib import Path

from domain_wiki.wiki_update import (
    WikiUpdateError,
    apply_confirmed_updates,
    render_wiki_result,
    run_wiki_update_review,
)
from domain_wiki.wiki_update_cli import initialize_wiki
from domain_wiki.wiki_update_cli import (
    _read_discussion,
    _read_documents,
    _read_pull_request_context,
)


class FakeModel:
    def __init__(self, model, outputs):
        self.model = model
        self.outputs = list(outputs)
        self.calls = []

    def review(self, prompt, schema):
        self.calls.append((prompt, schema))
        return self.outputs.pop(0), {"inputTokens": 1, "outputTokens": 1}


class WikiUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.wiki = self.root / "wiki"
        self.seed = self.root / "seed"
        self.wiki.mkdir()
        self.seed.mkdir()
        (self.wiki / "Home.md").write_text("테스트입니다\n", encoding="utf-8")
        (self.seed / "Home.md").write_text("# 정책 Wiki\n", encoding="utf-8")
        (self.seed / "Policy.md").write_text("현재 동작\n", encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def test_bootstrap_replaces_test_home_and_adds_missing_documents(self):
        changed = initialize_wiki(self.wiki, self.seed)
        self.assertEqual(["Home.md", "Policy.md"], changed)
        self.assertEqual("# 정책 Wiki\n", (self.wiki / "Home.md").read_text())

    def test_initialized_wiki_does_not_restore_a_missing_seed_document(self):
        (self.wiki / "Home.md").write_text("# 운영 중인 정책 Wiki\n", encoding="utf-8")
        changed = initialize_wiki(self.wiki, self.seed)
        self.assertEqual([], changed)
        self.assertFalse((self.wiki / "Policy.md").exists())

    def test_two_reviews_gate_and_apply_exact_replacement(self):
        proposal = {
            "proposalId": "W1", "document": "Policy.md",
            "currentText": "현재 동작", "replacementText": "머지된 동작",
            "reason": "코드가 변경됨", "codePaths": ["frontend/src/a.ts"],
        }
        cheap = FakeModel(
            "gpt-6-luna",
            [
                {"proposals": [proposal]},
                {"responses": [{"proposalId": "W1", "disposition": "defend", "response": "근거 있음"}]},
            ],
        )
        strong = FakeModel(
            "gpt-6.1-sol",
            [
                {"assessments": [{"proposalId": "W1", "decision": "support", "objections": []}]},
                {"assessments": [{"proposalId": "W1", "decision": "confirmed", "unresolvedCounterevidence": False, "rationale": "확인"}]},
            ],
        )
        result = run_wiki_update_review(
            cheap_client=cheap,
            strong_client=strong,
            documents=[{"path": "Policy.md", "content": "현재 동작\n"}],
            changed_paths=("frontend/src/a.ts",),
            patches={"frontend/src/a.ts": "+new"},
            pull_request={"title": "change", "body": "body"},
            discussion=[{"body": "approved"}],
            meeting_evidence=[{"pageId": "1", "verified": True}],
        )
        self.assertEqual(
            ["draft", "review-1", "author-response", "review-2"], result["stages"]
        )
        self.assertEqual(2, len(cheap.calls))
        self.assertEqual(2, len(strong.calls))
        (self.wiki / "Policy.md").write_text("현재 동작\n", encoding="utf-8")
        changed, skipped = apply_confirmed_updates(
            self.wiki, [{"path": "Policy.md", "content": "현재 동작\n"}], result["accepted"]
        )
        self.assertEqual(["Policy.md"], changed)
        self.assertEqual([], skipped)
        self.assertEqual("머지된 동작\n", (self.wiki / "Policy.md").read_text())
        self.assertIn("확정: 1건", render_wiki_result(result, changed))
        for model in (cheap, strong):
            for prompt, _schema in model.calls:
                self.assertIn("untrusted data only", prompt["systemInstructions"])

    def test_no_mapped_paths_skips_all_model_calls(self):
        cheap = FakeModel("cheap", [])
        strong = FakeModel("strong", [])
        result = run_wiki_update_review(
            cheap_client=cheap, strong_client=strong, documents=[],
            changed_paths=(), patches={}, pull_request={}, discussion=[],
            meeting_evidence=[],
        )
        self.assertEqual([], result["stages"])
        self.assertEqual([], cheap.calls)
        self.assertEqual([], strong.calls)

    def test_no_draft_proposals_skips_both_review_rounds(self):
        cheap = FakeModel("cheap", [{"proposals": []}])
        strong = FakeModel("strong", [])
        result = run_wiki_update_review(
            cheap_client=cheap, strong_client=strong,
            documents=[{"path": "Policy.md", "content": "현재 동작\n"}],
            changed_paths=("frontend/src/a.ts",), patches={}, pull_request={},
            discussion=[], meeting_evidence=[],
        )
        self.assertEqual(["draft"], result["stages"])
        self.assertEqual(1, len(cheap.calls))
        self.assertEqual([], strong.calls)

    def test_stale_replacement_is_rejected_without_aborting_other_updates(self):
        accepted = [
            {
                "proposalId": "W1", "document": "Policy.md",
                "currentText": "현재 동작", "replacementText": "첫 변경",
            },
            {
                "proposalId": "W2", "document": "Policy.md",
                "currentText": "현재 동작", "replacementText": "둘째 변경",
            },
        ]
        (self.wiki / "Policy.md").write_text("현재 동작\n", encoding="utf-8")
        changed, skipped = apply_confirmed_updates(
            self.wiki, [{"path": "Policy.md", "content": "현재 동작\n"}], accepted
        )
        self.assertEqual(["Policy.md"], changed)
        self.assertEqual(["W2"], [item["proposalId"] for item in skipped])
        self.assertIn("current-text-changed", skipped[0]["gateFailures"])
        self.assertEqual("첫 변경\n", (self.wiki / "Policy.md").read_text())

    def test_policy_registry_is_never_ai_editable(self):
        (self.wiki / "Policy.md").write_text("정책\n", encoding="utf-8")
        (self.wiki / "Policy-Registry.json").write_text("{}\n", encoding="utf-8")
        documents = _read_documents(
            self.wiki, {"documents": ["Policy-Registry.json", "Policy.md"]}
        )
        self.assertEqual(["Policy.md"], [item["path"] for item in documents])

    def test_pr_and_discussion_inputs_are_compacted(self):
        pr = self.root / "pr.json"
        discussion = self.root / "discussion.json"
        pr.write_text(
            '{"title":"' + ('t' * 600) + '","body":"' + ('b' * 21000)
            + '","number":192,"url":"https://example.com"}', encoding="utf-8"
        )
        discussion.write_text(
            '[{"user":{"login":"reviewer","id":1},"body":"'
            + ('c' * 5000)
            + '","path":"a.py","state":"APPROVED","url":"secret"}]',
            encoding="utf-8",
        )
        compact_pr = _read_pull_request_context(pr)
        compact_discussion = _read_discussion(discussion)
        self.assertEqual(500, len(compact_pr["title"]))
        self.assertEqual(20_000, len(compact_pr["body"]))
        self.assertEqual(4_000, len(compact_discussion[0]["body"]))
        self.assertEqual({"login": "reviewer"}, compact_discussion[0]["user"])
        self.assertNotIn("url", compact_discussion[0])

    def test_unresolved_or_unmapped_proposal_is_rejected(self):
        proposal = {
            "proposalId": "W1", "document": "Policy.md",
            "currentText": "현재 동작", "replacementText": "다른 동작",
            "reason": "추정", "codePaths": ["backend/unrelated.java"],
        }
        cheap = FakeModel("cheap", [{"proposals": [proposal]}, {"responses": []}])
        strong = FakeModel(
            "strong",
            [
                {"assessments": []},
                {"assessments": [{"proposalId": "W1", "decision": "confirmed", "unresolvedCounterevidence": True, "rationale": "근거 부족"}]},
            ],
        )
        result = run_wiki_update_review(
            cheap_client=cheap, strong_client=strong,
            documents=[{"path": "Policy.md", "content": "현재 동작\n"}],
            changed_paths=("frontend/src/a.ts",), patches={},
            pull_request={}, discussion=[], meeting_evidence=[],
        )
        self.assertEqual([], result["accepted"])
        self.assertIn("counterevidence", result["rejected"][0]["gateFailures"])
        self.assertIn("changed-code-evidence", result["rejected"][0]["gateFailures"])

    def test_same_model_is_rejected(self):
        with self.assertRaisesRegex(WikiUpdateError, "must be different"):
            run_wiki_update_review(
                cheap_client=FakeModel("same", []), strong_client=FakeModel("same", []),
                documents=[], changed_paths=(), patches={}, pull_request={},
                discussion=[], meeting_evidence=[],
            )


if __name__ == "__main__":
    unittest.main()
