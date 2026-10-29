import { describe, expect, it } from "@jest/globals";
import type { FailedTest } from "./failures.ts";
import { noticeFor, summaryFor } from "./notify.ts";
import type { DevOverlap, HealerReport, RunSummary } from "./state.ts";

const failure: FailedTest = {
  file: "board-map.spec.ts",
  line: 1463,
  project: "chromium",
  titlePath: ["확대한다"],
  errorContext: null,
};

const summary: RunSummary = {
  runId: 40259023105,
  url: "https://github.com/woowacourse-teams/2026-salmonbus/actions/runs/40259023105",
  branch: "fe/test/SAL-265",
  sha: "55b062a03403fcbe024eecb01d26cc0cc31241c9",
  failures: [failure, { ...failure, line: 1684 }],
};

const ownFailure: FailedTest = { ...failure, file: "route-select.spec.ts", titlePath: ["결과 없음을 알린다"] };

const devOverlap: DevOverlap = { devRunId: 40259011111, sharedFailures: [failure] };

function createHealer(overrides: Partial<HealerReport>): HealerReport {
  return { agent: "claude", verdicts: ["테스트 수정"], seconds: 42, changedFiles: [], warnings: [], ...overrides };
}

describe("noticeFor", () => {
  it("healer 판정과 실패 수를 제목에 E2E:로 시작해 적는다", () => {
    const notice = noticeFor({
      ...summary,
      outcome: "healed",
      healer: createHealer({ verdicts: ["제품 버그 의심"] }),
      devOverlap: null,
    });

    expect(notice.title).toBe("E2E: 실패 2개, 제품 버그 의심");
    expect(notice.subtitle).toBe("fe/test/SAL-265");
    expect(notice.message).toBe("테스트는 그대로예요. 자세히: pnpm ci:watch --run 40259023105");
  });

  it("테스트를 고쳤으면 바꾼 파일을 알려 준다", () => {
    const healer = createHealer({ changedFiles: ["frontend/e2e/board-map.spec.ts"] });

    expect(noticeFor({ ...summary, outcome: "healed", healer, devOverlap: null }).message).toBe(
      "e2e/board-map.spec.ts를 고쳤어요. 자세히: pnpm ci:watch --run 40259023105",
    );
  });

  it("경고가 있으면 바꾼 파일보다 경고를 먼저 보여 준다", () => {
    const healer = createHealer({ changedFiles: ["frontend/src/app/App.tsx"], warnings: ["e2e 밖의 파일을 바꿨어요"] });

    expect(noticeFor({ ...summary, outcome: "healed", healer, devOverlap: null }).message).toMatch(
      /^확인 필요: e2e 밖의 파일을 바꿨어요/,
    );
  });

  it("판정이 섞이면 무거운 판정부터 모두 제목에 적는다", () => {
    const healer = createHealer({ verdicts: ["제품 버그 의심", "테스트 수정"] });

    expect(noticeFor({ ...summary, outcome: "healed", healer, devOverlap: null }).title).toBe(
      "E2E: 실패 2개, 제품 버그 의심, 테스트 수정",
    );
  });

  it("dev와 일부만 겹치면 이 브랜치에서만 실패한 테스트의 판정과 dev 쪽 문제 수를 함께 알린다", () => {
    const healer = createHealer({ changedFiles: ["frontend/e2e/route-select.spec.ts"] });
    const notice = noticeFor({
      ...summary,
      failures: [failure, ownFailure, { ...ownFailure, project: "iphone-15" }],
      outcome: "healed",
      healer,
      devOverlap,
    });

    expect(notice.title).toBe("E2E: 실패 3개, 테스트 수정, dev 쪽 문제 1개");
    expect(notice.message).toBe(
      "e2e/route-select.spec.ts를 고쳤어요. dev push에서도 실패한 1개는 healer에게 넘기지 않았어요. 자세히: pnpm ci:watch --run 40259023105",
    );
  });

  it("dev에서도 모두 실패했으면 dev 쪽 문제로 알린다", () => {
    const notice = noticeFor({
      ...summary,
      outcome: "dev-problem",
      devRunId: 40259011111,
      sharedFailures: summary.failures,
    });

    expect(notice.title).toBe("E2E: 실패 2개, dev 쪽 문제");
    expect(notice.message).toBe("dev push에서도 2개가 실패해요. 자세히: pnpm ci:watch --run 40259023105");
  });

  it("E2E 실패가 없으면 다른 CI 단계를 보라고 알린다", () => {
    expect(noticeFor({ ...summary, failures: [], outcome: "not-e2e" }).title).toBe("E2E: 실패 없음, 다른 CI 단계 실패");
  });
});

describe("summaryFor", () => {
  it("테스트를 고쳤으면 diff를 적용하는 명령을 적는다", () => {
    const healer = createHealer({ changedFiles: ["frontend/e2e/board-map.spec.ts"] });
    const text = summaryFor({ ...summary, outcome: "healed", healer, devOverlap: null }, "판정: 테스트 수정");

    expect(text).toMatch(/- 리포 루트에서 적용: git apply .*\/40259023105\/healer\.diff/);
    expect(text).toContain("### healer 보고\n\n판정: 테스트 수정");
  });

  it("고친 파일이 없으면 적용 명령을 적지 않는다", () => {
    const text = summaryFor({
      ...summary,
      outcome: "healed",
      healer: createHealer({ verdicts: ["판단 못 함"] }),
      devOverlap: null,
    });

    expect(text).not.toContain("git apply");
    expect(text).toContain("(보고 없음)");
  });

  it("dev에서도 실패한 테스트는 healer에게 넘기지 않았다고 따로 적고 healer 보고도 남긴다", () => {
    const text = summaryFor(
      { ...summary, failures: [failure, ownFailure], outcome: "healed", healer: createHealer({}), devOverlap },
      "판정: 테스트 수정",
    );

    expect(text).toContain(
      [
        "## 실패한 테스트",
        "",
        "- [chromium] e2e/board-map.spec.ts:1463 › 확대한다",
        "- [chromium] e2e/route-select.spec.ts:1463 › 결과 없음을 알린다",
      ].join("\n"),
    );
    expect(text).toContain(
      [
        "## dev push 실행 40259011111에서도 실패한 테스트",
        "",
        "- [chromium] e2e/board-map.spec.ts:1463 › 확대한다",
        "",
        "healer에게 넘기지 않았어요. dev가 고쳐지면 dev를 병합하고 다시 푸시해 주세요.",
      ].join("\n"),
    );
    expect(text).toContain("### healer 보고\n\n판정: 테스트 수정");
  });
});
