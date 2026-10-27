import { describe, expect, it } from "@jest/globals";
import type { FailedTest } from "./failures.ts";
import { promptFor, verdictFrom, warningsFor } from "./healer.ts";

const failure: FailedTest = {
  file: "board-map.spec.ts",
  line: 1463,
  project: "iphone-15",
  titlePath: ["확대한다"],
  errorContext: "data/first.md",
};

describe("verdictFrom", () => {
  it("보고 형식의 판정 줄을 읽는다", () => {
    expect(verdictFrom("판정: 제품 버그 의심\n실패한 테스트: ...")).toBe("제품 버그 의심");
  });

  it("마크다운 강조가 붙어도 판정을 읽는다", () => {
    expect(verdictFrom("**판정:** `테스트 수정`")).toBe("테스트 수정");
  });

  it("판정 줄이 여러 개면 마지막 판정을 쓴다", () => {
    expect(verdictFrom("판정: 테스트 수정 | 제품 버그 의심 | 판단 못 함\n...\n판정: 판단 못 함")).toBe("판단 못 함");
  });

  it("보고 형식이 없으면 null", () => {
    expect(verdictFrom("테스트를 고쳤습니다.")).toBeNull();
  });
});

describe("warningsFor", () => {
  it("e2e 안의 파일만 고친 테스트 수정은 경고가 없다", () => {
    expect(warningsFor("테스트 수정", ["frontend/e2e/board-map.spec.ts"])).toEqual([]);
  });

  it("e2e 밖을 고치면 경고한다", () => {
    expect(warningsFor("테스트 수정", ["frontend/src/app/App.tsx"])).toEqual([
      "e2e 밖의 파일을 바꿨어요: frontend/src/app/App.tsx",
    ]);
  });

  it("제품 버그 의심인데 바뀐 파일이 있으면 경고한다", () => {
    expect(warningsFor("제품 버그 의심", ["frontend/e2e/board-map.spec.ts"])).toEqual([
      "판정이 제품 버그 의심인데 바뀐 파일이 있어요.",
    ]);
  });

  it("판정을 찾지 못하면 경고한다", () => {
    expect(warningsFor(null, [])).toEqual(["healer 보고에서 판정을 찾지 못했어요."]);
  });
});

describe("promptFor", () => {
  it("Codex에는 healer 에이전트 이름을 적고, 첫 실패 기록을 절대 경로로 알려 준다", () => {
    const prompt = promptFor("codex", [failure], "/state/123/artifact/playwright-report");

    expect(prompt).toContain("playwright_test_healer 에이전트로 실패한 테스트를 고쳐 주세요.");
    expect(prompt).toContain("로컬 재현은 `test_run` 도구로 해 주세요.");
    expect(prompt).toContain("- [iphone-15] e2e/board-map.spec.ts:1463 › 확대한다");
    expect(prompt).toContain("CI 첫 실패 기록: /state/123/artifact/playwright-report/data/first.md");
  });

  it("Claude는 healer 에이전트로 바로 띄우므로 에이전트 이름을 적지 않는다", () => {
    expect(promptFor("claude", [failure], "/report")).not.toContain("playwright_test_healer");
  });
});
