import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from domain_wiki.wiki_update import (
    WikiUpdateError,
    apply_confirmed_updates,
    create_approval_request,
    merge_wiki_update_results,
    render_wiki_result,
    run_wiki_update_review,
    validate_approval_request,
    _gate_failures,
)
from domain_wiki.wiki_update_cli import initialize_wiki, main as wiki_update_main
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
    def test_rejected_duplicate_wins_when_batch_results_are_merged(self):
        proposal = {
            "proposalId": "P1",
            "document": "Policy.md",
            "currentText": "old",
            "replacementText": "new",
        }
        base = {"accepted": [], "rejected": [], "usage": []}
        merged = merge_wiki_update_results([
            {**base, "accepted": [proposal]},
            {**base, "rejected": [{**proposal, "reason": "근거 부족"}]},
        ])

        self.assertEqual([], merged["accepted"])
        self.assertEqual("B2-P1", merged["rejected"][0]["proposalId"])
        self.assertEqual(2, merged["batchCount"])

    def test_conflicting_batch_replacements_are_rejected(self):
        proposal = {
            "proposalId": "P1",
            "document": "Policy.md",
            "currentText": "old",
            "replacementText": "first",
        }
        base = {"accepted": [], "rejected": [], "usage": []}
        merged = merge_wiki_update_results([
            {**base, "accepted": [proposal]},
            {**base, "accepted": [{**proposal, "replacementText": "second"}]},
        ])

        self.assertEqual([], merged["accepted"])
        self.assertEqual(2, len(merged["rejected"]))
        self.assertTrue(all(
            "batch-conflict" in item["gateFailures"]
            for item in merged["rejected"]
        ))

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

    def test_discussion_budget_keeps_the_latest_review_evidence(self):
        discussion = self.root / "discussion-budget.json"
        discussion.write_text(
            json.dumps([
                {
                    "user": {"login": "older"}, "body": "o" * 20,
                    "created_at": "2026-10-01T00:00:00Z",
                },
                {
                    "user": {"login": "latest"}, "body": "최종 승인",
                    "submitted_at": "2026-10-02T00:00:00Z", "state": "APPROVED",
                },
            ], ensure_ascii=False),
            encoding="utf-8",
        )
        compact = _read_discussion(
            discussion, max_characters=len("최종 승인"), max_body_characters=100
        )
        self.assertEqual(["latest"], [item["user"]["login"] for item in compact])
        self.assertEqual("APPROVED", compact[0]["state"])

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

    def test_author_withdrawal_cannot_be_overridden_by_final_review(self):
        proposal = {
            "proposalId": "W1", "document": "Policy.md",
            "currentText": "현재 동작", "replacementText": "다른 동작",
            "reason": "초안", "codePaths": ["frontend/src/a.ts"],
        }
        cheap = FakeModel(
            "cheap",
            [
                {"proposals": [proposal]},
                {"responses": [{
                    "proposalId": "W1", "disposition": "withdraw",
                    "response": "근거가 없어 철회",
                }]},
            ],
        )
        strong = FakeModel(
            "strong",
            [
                {"assessments": [{
                    "proposalId": "W1", "decision": "support", "objections": [],
                }]},
                {"assessments": [{
                    "proposalId": "W1", "decision": "confirmed",
                    "unresolvedCounterevidence": False, "rationale": "확인",
                }]},
            ],
        )
        result = run_wiki_update_review(
            cheap_client=cheap, strong_client=strong,
            documents=[{"path": "Policy.md", "content": "현재 동작\n"}],
            changed_paths=("frontend/src/a.ts",), patches={}, pull_request={},
            discussion=[], meeting_evidence=[],
        )
        self.assertEqual([], result["accepted"])
        self.assertIn("author-withdrawn", result["rejected"][0]["gateFailures"])

    def test_author_revision_requires_a_new_draft(self):
        failures = _gate_failures(
            {
                "document": "Policy.md", "currentText": "현재 동작",
                "replacementText": "수정 전 문장", "codePaths": ["frontend/src/a.ts"],
            },
            {"decision": "confirmed", "unresolvedCounterevidence": False},
            {"disposition": "revise", "response": "문장을 바꿔야 함"},
            [{"path": "Policy.md", "content": "현재 동작\n"}],
            ("frontend/src/a.ts",),
        )
        self.assertIn("author-revision-not-redrafted", failures)

    def test_approval_digest_binds_proposals_and_wiki_commit(self):
        result = {"accepted": [{
            "proposalId": "W1", "document": "Policy.md",
            "currentText": "현재 동작", "replacementText": "새 동작",
        }], "rejected": []}
        request = create_approval_request(
            result=result, base_sha="base", head_sha="head",
            wiki_commit="wiki", bootstrap_files=("Home.md",),
            pull_request={"number": 192},
        )
        accepted = validate_approval_request(
            request, request["approvalDigest"], "wiki"
        )
        self.assertEqual("W1", accepted[0]["proposalId"])
        rendered = render_wiki_result(result, [], request)
        self.assertIn("사람 승인 대기", rendered)
        self.assertIn("반영 예정 문장", rendered)
        self.assertIn("현재 동작", rendered)
        self.assertIn("새 동작", rendered)
        self.assertIn("Approve and deploy", rendered)
        self.assertNotIn("승인값:", rendered)

        tampered = {**request, "accepted": [{**request["accepted"][0], "replacementText": "조작"}]}
        with self.assertRaisesRegex(WikiUpdateError, "digest"):
            validate_approval_request(tampered, request["approvalDigest"], "wiki")
        with self.assertRaisesRegex(WikiUpdateError, "Wiki changed"):
            validate_approval_request(request, request["approvalDigest"], "new-wiki")

    def test_cli_applies_only_the_exact_approved_request(self):
        repository = self.root / "repository"
        repository.mkdir()
        subprocess.run(["git", "init", "-q", str(repository)], check=True)
        subprocess.run(["git", "-C", str(repository), "config", "user.email", "test@example.com"], check=True)
        subprocess.run(["git", "-C", str(repository), "config", "user.name", "Test"], check=True)
        (repository / "code.txt").write_text("before\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(repository), "add", "."], check=True)
        subprocess.run(["git", "-C", str(repository), "commit", "-qm", "base"], check=True)
        base = subprocess.check_output(
            ["git", "-C", str(repository), "rev-parse", "HEAD"], text=True
        ).strip()
        (repository / "code.txt").write_text("after\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(repository), "commit", "-qam", "head"], check=True)
        head = subprocess.check_output(
            ["git", "-C", str(repository), "rev-parse", "HEAD"], text=True
        ).strip()

        (self.wiki / "Home.md").write_text("# 정책 Wiki\n", encoding="utf-8")
        (self.wiki / "Policy.md").write_text("현재 동작\n", encoding="utf-8")
        subprocess.run(["git", "init", "-q", str(self.wiki)], check=True)
        subprocess.run(["git", "-C", str(self.wiki), "config", "user.email", "test@example.com"], check=True)
        subprocess.run(["git", "-C", str(self.wiki), "config", "user.name", "Test"], check=True)
        subprocess.run(["git", "-C", str(self.wiki), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.wiki), "commit", "-qm", "wiki"], check=True)
        wiki_commit = subprocess.check_output(
            ["git", "-C", str(self.wiki), "rev-parse", "HEAD"], text=True
        ).strip()
        (self.seed / "Wiki-Document-Registry.json").write_text(
            '{"documents":["Policy.md"],"codePaths":["code.txt"]}\n',
            encoding="utf-8",
        )
        request = create_approval_request(
            result={"accepted": [{
                "proposalId": "W1", "document": "Policy.md",
                "currentText": "현재 동작", "replacementText": "새 동작",
            }]},
            base_sha=base, head_sha=head, wiki_commit=wiki_commit,
        )
        approval = self.root / "approval.json"
        approval.write_text(json.dumps(request), encoding="utf-8")
        output = self.root / "output"
        wiki_update_main([
            "--repository", str(repository), "--base", base, "--head", head,
            "--wiki-directory", str(self.wiki), "--seed-directory", str(self.seed),
            "--pull-request-context", "/dev/null", "--discussion", "/dev/null",
            "--output-directory", str(output), "--apply-approval", str(approval),
            "--approval-digest", request["approvalDigest"],
        ])
        self.assertEqual("새 동작\n", (self.wiki / "Policy.md").read_text())
        applied = json.loads((output / "wiki-result.json").read_text())
        self.assertEqual("applied", applied["status"])

    def test_same_model_is_rejected(self):
        with self.assertRaisesRegex(WikiUpdateError, "must be different"):
            run_wiki_update_review(
                cheap_client=FakeModel("same", []), strong_client=FakeModel("same", []),
                documents=[], changed_paths=(), patches={}, pull_request={},
                discussion=[], meeting_evidence=[],
            )


if __name__ == "__main__":
    unittest.main()
