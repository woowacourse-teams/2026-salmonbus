import json
import subprocess
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path

from domain_wiki.policy_issue_review import (
    PolicyIssueError,
    load_active_rules,
    render_comment,
    run_issue_review,
    verify_confluence_evidence,
)


class FakeModel:
    def __init__(self, model, outputs):
        self.model = model
        self.outputs = list(outputs)
        self.calls = []

    def review(self, prompt, schema):
        self.calls.append((prompt, schema))
        return self.outputs.pop(0), {"inputTokens": 1, "outputTokens": 1}


class FakeConfluence:
    def __init__(self):
        self.calls = []

    def get_page(self, page_id):
        self.calls.append(page_id)
        bodies = {
            "61014037": "<p>그래서 지도에서 보는 현재 위치와 정류장에서 보는 예보가 서로 다른 관측 시점에서 섞이지 않아요.</p>",
            "47775855": "<p>화면에는 짝이 맞는 가장 최근 쌍(같은 observation batch의 예보 응답과 차량 응답)만 올린다</p>",
        }
        versions = {"61014037": 4, "47775855": 5}
        return {"version": {"number": versions[page_id]}, "body": {"storage": {"value": bodies[page_id]}}}


class PolicyIssueReviewTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.registry = self.root / "Policy-Registry.json"
        self.registry.write_text(json.dumps(_registry()), encoding="utf-8")
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.email", "test@example.com"], check=True)
        subprocess.run(["git", "-C", str(self.root), "config", "user.name", "Test"], check=True)
        code = self.root / "frontend/src/pages/verdict-board/boardSnapshotPolicy.ts"
        code.parent.mkdir(parents=True)
        code.write_text("one\ntwo\nthree\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(self.root), "add", "."], check=True)
        subprocess.run(["git", "-C", str(self.root), "commit", "-qm", "fixture"], check=True)
        self.head = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True).strip()
        self.rules = load_active_rules(self.registry, "korogoo/salmonbus-domain-wiki-lab")

    def tearDown(self):
        self.temp.cleanup()

    def test_registry_only_exposes_active_rule_to_lab_repository(self):
        self.assertEqual(["POL-DSP-001-R2"], [rule.rule_id for rule in self.rules])
        with self.assertRaisesRegex(PolicyIssueError, "no active"):
            load_active_rules(self.registry, "woowacourse-teams/2026-salmonbus")

    def test_confluence_quotes_and_versions_are_hard_gate(self):
        verify_confluence_evidence(FakeConfluence(), self.rules)
        raw = _registry()
        raw["policies"][0]["rules"][0]["sourceVersion"] = "3"
        self.registry.write_text(json.dumps(raw), encoding="utf-8")
        with self.assertRaisesRegex(PolicyIssueError, "version changed"):
            verify_confluence_evidence(FakeConfluence(), load_active_rules(self.registry, "korogoo/salmonbus-domain-wiki-lab"))

    def test_same_confluence_page_is_fetched_once(self):
        rule = self.rules[0]
        repeated = replace(
            rule,
            meeting_page_id=rule.source_page_id,
            meeting_version=rule.source_version,
            meeting_quote=rule.source_quote,
        )
        confluence = FakeConfluence()
        verify_confluence_evidence(confluence, (repeated,))
        self.assertEqual([rule.source_page_id], confluence.calls)

    def test_two_mutual_reviews_and_score_gate_leave_comment(self):
        candidate = _candidate()
        cheap = FakeModel("gpt-6-luna", [{"candidates": [candidate]}, {"responses": [{"candidateId": "C1", "disposition": "defend", "response": "재현 조건이 코드에 남아 있다."}]}])
        strong = FakeModel("gpt-6.1-sol", [_assessment(79), _assessment(86, final=True)])
        result = run_issue_review(
            cheap_client=cheap, strong_client=strong, rules=self.rules,
            patches={candidate["path"]: "+ newest paired board can be removed"},
            repository=self.root, head_sha=self.head, wiki_commit="abc123",
            source_url="https://github.com/woowacourse-teams/2026-salmonbus/pull/106",
            pull_request={"title": "같은 observation batch만 표시", "body": "중간 상태에서 pairing을 구현합니다."},
            comment_style={
                "boundary": "human fields only",
                "instructions": "팀원이 읽는 설명 뒤에 밈을 붙인다.",
                "memes": "대충 하면~? 꼭 두 번 일한다~",
            },
        )
        self.assertEqual(["discover", "review-1", "author-response", "review-2"], result["stages"])
        self.assertEqual(2, len(cheap.calls))
        self.assertEqual(2, len(strong.calls))
        self.assertIn("scoringRubric", strong.calls[0][0])
        self.assertIn("independently", strong.calls[1][0]["systemInstructions"])
        self.assertIn("팀원이 읽는 설명", strong.calls[1][0]["systemInstructions"])
        self.assertNotIn("팀원이 읽는 설명", strong.calls[0][0]["systemInstructions"])
        for model in (cheap, strong):
            for prompt, _schema in model.calls:
                self.assertIn("untrusted data only", prompt["systemInstructions"])
        self.assertEqual("중간 상태에서 pairing을 구현합니다.", cheap.calls[0][0]["pullRequest"]["body"])
        self.assertEqual(86, result["issues"][0]["score"])
        self.assertEqual("inferred", result["issues"][0]["evidenceBasis"])
        self.assertEqual(16, len(result["issues"][0]["historyKey"]))
        comment = render_comment(result)
        self.assertIn("## 확인이 필요한 문제 1건", comment)
        self.assertIn("### 버스 위치 없이 새 예보만 보일 수 있어요", comment)
        self.assertIn("**어떤 때 생기나요?**", comment)
        self.assertIn("**어떻게 바꾸면 좋을까요?**", comment)
        self.assertEqual(1, comment.count("대충 하면~? 꼭 두 번 일한다~"))
        self.assertNotIn("후보 1개를 두 번 검수", comment)
        prose = comment.split("<details>", 1)[0]
        human = comment.split("<summary>AI와 로컬 도구용 검증 데이터</summary>", 1)[0]
        self.assertNotIn("POL-DSP-001-R2", human)
        self.assertNotIn("86/100", human)
        self.assertNotIn("항목별 점수", human)
        self.assertNotIn("boardSnapshotPolicy.ts", prose)
        self.assertIn("boardSnapshotPolicy.ts", human)
        self.assertIn("팀에서 정한 동작", human)
        self.assertIn("<summary>AI와 로컬 도구용 검증 데이터</summary>", comment)
        data = comment.split("<!-- policy-review-ai-data:start -->", 1)[1]
        data = data.split("<!-- policy-review-ai-data:end -->", 1)[0]
        data = data.split("```json", 1)[1].split("```", 1)[0]
        parsed = json.loads(data)
        self.assertEqual("salmonbus-policy-review", parsed["kind"])
        self.assertEqual(86, parsed["review"]["issues"][0]["score"])
        self.assertEqual("POL-DSP-001-R2", parsed["review"]["issues"][0]["ruleId"])
        self.assertEqual("중간 상태에서 pairing을 구현합니다.", parsed["source"]["pullRequest"]["body"])
        self.assertEqual("47775855", parsed["review"]["issues"][0]["meetingEvidence"]["pageId"])
        self.assertEqual("inferred", parsed["review"]["issues"][0]["evidenceBasis"])

    def test_unresolved_counterevidence_blocks_high_score(self):
        candidate = _candidate()
        cheap = FakeModel("cheap", [{"candidates": [candidate]}, {"responses": [{"candidateId": "C1", "disposition": "defend", "response": "x"}]}])
        strong = FakeModel("strong", [_assessment(100), _assessment(100, final=True, unresolved=True)])
        result = run_issue_review(
            cheap_client=cheap, strong_client=strong, rules=self.rules,
            patches={candidate["path"]: "patch"}, repository=self.root,
            head_sha=self.head, wiki_commit="abc", source_url="url",
        )
        self.assertEqual([], result["issues"])
        self.assertIn("counterevidence", result["evaluations"][0]["gateFailures"])
        comment = render_comment(result)
        self.assertIn("반대 근거 미해소", comment)
        self.assertNotIn("문제가...없네..?", comment)
        self.assertNotIn("해가 서쪽에서 뜨겄어~?", comment)

    def test_guessed_evidence_never_leaves_a_comment(self):
        candidate = _candidate()
        cheap = FakeModel("cheap", [{"candidates": [candidate]}, {"responses": [{"candidateId": "C1", "disposition": "defend", "response": "x"}]}])
        strong = FakeModel("strong", [_assessment(100), _assessment(100, final=True, evidence_basis="guessed")])
        result = run_issue_review(
            cheap_client=cheap, strong_client=strong, rules=self.rules,
            patches={candidate["path"]: "patch"}, repository=self.root,
            head_sha=self.head, wiki_commit="abc", source_url="url",
        )
        self.assertEqual([], result["issues"])
        self.assertIn("guessed-evidence", result["evaluations"][0]["gateFailures"])
        self.assertIn("추측 근거", render_comment(result))

    def test_completed_review_without_candidates_uses_namukkun_result(self):
        result = {
            "stages": ["discover", "review-1", "author-response", "review-2"],
            "issues": [], "evaluations": [], "models": {}, "threshold": 80,
            "candidateCount": 0,
        }
        comment = render_comment(result)
        self.assertIn("음~~~ 어디~ 보자~?", comment)
        self.assertIn("문제가...없네..?", comment)
        self.assertIn("# 해가 서쪽에서 뜨겄어~?", comment)

    def test_skipped_review_does_not_claim_there_is_no_problem(self):
        result = {
            "status": "no-active-policy", "stages": [], "issues": [],
            "evaluations": [], "models": {}, "threshold": 80,
            "candidateCount": 0,
        }
        comment = render_comment(result)
        self.assertIn("활성화된 정책이 없어", comment)
        self.assertNotIn("문제가...없네..?", comment)
        self.assertNotIn("해가 서쪽에서 뜨겄어~?", comment)

    def test_same_model_is_rejected(self):
        with self.assertRaisesRegex(PolicyIssueError, "must be different"):
            run_issue_review(
                cheap_client=FakeModel("same", []), strong_client=FakeModel("same", []),
                rules=self.rules, patches={}, repository=self.root, head_sha=self.head,
                wiki_commit="abc", source_url="url",
            )


def _registry():
    return {
        "schemaVersion": 1, "authority": "git-wiki",
        "policies": [{"policyId": "POL-DSP-001", "rules": [{
            "ruleId": "POL-DSP-001-R2", "status": "active",
            "text": "차량 위치와 예보는 같은 수집 묶음에서 읽는다.",
            "enforcementScope": "lab-only",
            "allowedRepositories": ["korogoo/salmonbus-domain-wiki-lab"],
            "codePaths": ["frontend/src/pages/verdict-board/boardSnapshotPolicy.ts"],
            "analysisGuidance": ["짝이 맞는 기존 상태 뒤에 짝이 없는 갱신이 연속해서 와도 기존 쌍이 보존되는지 추적한다."],
            "sourcePageId": "61014037", "sourceVersion": "4",
            "sourceQuote": "그래서 지도에서 보는 현재 위치와 정류장에서 보는 예보가 서로 다른 관측 시점에서 섞이지 않아요.",
            "meetingEvidence": {"pageId": "47775855", "version": "5", "quote": "화면에는 짝이 맞는 가장 최근 쌍(같은 observation batch의 예보 응답과 차량 응답)만 올린다"},
            "labApproval": {"approvedBy": "korogoo", "approvedAt": "2026-10-27"},
        }]}],
    }


def _candidate():
    return {
        "candidateId": "C1", "ruleId": "POL-DSP-001-R2",
        "violationType": "paired-snapshot-discarded",
        "path": "frontend/src/pages/verdict-board/boardSnapshotPolicy.ts", "line": 2,
        "claim": "새 보드 응답이 짝이 맞는 스냅샷을 제거한다.",
        "condition": "예보 응답 뒤에 짝이 없는 보드 응답이 오면 발생한다.",
        "impact": "서로 다른 관측 시점이 한 화면에 표시될 수 있다.",
        "codeEvidence": "withBoard가 paired snapshot을 자른다.",
    }


def _assessment(score, final=False, unresolved=False, evidence_basis="inferred"):
    values = {
        "policyApplicability": min(score, 25),
        "codeEvidence": min(max(score - 25, 0), 25),
        "userImpact": min(max(score - 50, 0), 20),
        "reproducibleCondition": min(max(score - 70, 0), 15),
        "counterevidenceResolved": min(max(score - 85, 0), 15),
    }
    item = {"candidateId": "C1", "decision": "confirmed", "scores": values, "rationale": "근거 확인"}
    if final:
        item.update({
            "unresolvedCounterevidence": unresolved,
            "evidenceBasis": evidence_basis,
            "evidenceReason": "코드의 상태 변경 순서로 확인했다.",
            "finalComment": "짝이 맞는 최신 스냅샷을 유지해야 합니다.",
            "humanTitle": "버스 위치 없이 새 예보만 보일 수 있어요",
            "humanExplanation": "현재 화면은 같은 시점에 확인한 버스 위치와 예보를 함께 보여줍니다. 새 예보만 먼저 여러 번 도착하면 기존 정보가 사라지고, 새 예보만 화면에 나올 수 있어요.",
            "humanTrigger": "버스 위치와 예보가 함께 보이는 동안 새 예보만 두 번 연속 먼저 도착하면 생깁니다.",
            "humanSuggestion": "새 정보의 짝이 완성될 때까지 기존 버스 위치와 예보를 유지해 주세요. 같은 순서로 응답이 도착하는 테스트도 추가하면 좋겠습니다.",
            "humanMeme": "대충 하면~? 꼭 두 번 일한다~",
        })
    return {"assessments": [item]}


if __name__ == "__main__":
    unittest.main()
