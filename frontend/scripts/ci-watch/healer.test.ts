import { describe, expect, it } from "@jest/globals";
import type { FailedTest } from "./failures.ts";
import { healerResultFor, promptFor, warningsFor } from "./healer.ts";

const failure: FailedTest = {
  file: "board-map.spec.ts",
  line: 1463,
  project: "iphone-15",
  titlePath: ["확대한다"],
  errorContext: "data/first.md",
};

const finished = { exitCode: 0, timedOut: false };

describe("healerResultFor", () => {
  it("테스트마다 따로 보고한 판정을 모두 모아 무거운 판정부터 돌려준다", () => {
    const report = "판정: 테스트 수정\n실패한 테스트: A\n\n판정: 제품 버그 의심\n실패한 테스트: B";

    expect(healerResultFor(finished, report)).toEqual({ verdicts: ["제품 버그 의심", "테스트 수정"] });
  });

  it("마크다운 강조가 붙어도 판정을 읽는다", () => {
    expect(healerResultFor(finished, "**판정:** `테스트 수정`")).toEqual({ verdicts: ["테스트 수정"] });
  });

  it("보고 형식을 옮겨 적은 줄은 판정으로 세지 않는다", () => {
    const report = "판정: 테스트 수정 | 제품 버그 의심 | 판단 못 함\n...\n판정: 판단 못 함";

    expect(healerResultFor(finished, report)).toEqual({ verdicts: ["판단 못 함"] });
  });

  it("같은 판정을 여러 번 적어도 한 번만 센다", () => {
    expect(healerResultFor(finished, "판정: 제품 버그 의심\n...\n판정: 제품 버그 의심")).toEqual({
      verdicts: ["제품 버그 의심"],
    });
  });

  it("정상으로 끝났어도 판정이 없으면 실패로 본다", () => {
    expect(healerResultFor(finished, "테스트를 고쳤습니다.")).toEqual({
      failure: "healer 보고에서 판정을 찾지 못했어요.",
    });
  });

  it("종료 코드가 0이 아니면 판정이 있어도 실패로 본다", () => {
    expect(healerResultFor({ exitCode: 1, timedOut: false }, "판정: 테스트 수정")).toEqual({
      failure: "healer가 종료 코드 1로 끝났어요.",
    });
  });

  it("30분 안에 끝나지 않으면 실패로 본다", () => {
    expect(healerResultFor({ exitCode: null, timedOut: true }, "")).toEqual({
      failure: "healer가 30분 안에 끝나지 않아 멈췄어요.",
    });
  });
});

describe("warningsFor", () => {
  it("e2e 안의 파일만 고친 테스트 수정은 경고가 없다", () => {
    expect(warningsFor(["테스트 수정"], ["frontend/e2e/board-map.spec.ts"])).toEqual([]);
  });

  it("e2e 밖을 고치면 경고한다", () => {
    expect(warningsFor(["테스트 수정"], ["frontend/src/app/App.tsx"])).toEqual([
      "e2e 밖의 파일을 바꿨어요: frontend/src/app/App.tsx",
    ]);
  });

  it("제품 버그 의심인데 바뀐 파일이 있으면 경고한다", () => {
    expect(warningsFor(["제품 버그 의심"], ["frontend/e2e/board-map.spec.ts"])).toEqual([
      "판정이 제품 버그 의심인데 바뀐 파일이 있어요.",
    ]);
  });

  it("판정이 섞였는데 바뀐 파일이 있으면 테스트마다 판정을 확인하라고 경고한다", () => {
    expect(warningsFor(["제품 버그 의심", "테스트 수정"], ["frontend/e2e/board-map.spec.ts"])).toEqual([
      "판정이 섞여 있어요. 바뀐 파일을 가져오기 전에 테스트마다 판정을 확인해 주세요.",
    ]);
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
