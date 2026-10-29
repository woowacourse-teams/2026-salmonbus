import { describe, expect, it } from "@jest/globals";
import { failedTestsFrom, failureSplitFrom, testLabelOf, type FailedTest, type PlaywrightReport } from "./failures.ts";

function createFailure(overrides: Partial<FailedTest>): FailedTest {
  return {
    file: "board-map.spec.ts",
    line: 1463,
    project: "chromium",
    titlePath: ["뒤쪽 차량 응답에는 지도와 목록이 멈춘다"],
    errorContext: null,
    ...overrides,
  };
}

const report: PlaywrightReport = {
  stats: { unexpected: 1 },
  files: [
    {
      tests: [
        {
          title: "펼치고 접어도 위치를 유지한다",
          path: ["판정 보드"],
          projectName: "iphone-15",
          location: { file: "board-map.spec.ts", line: 1684 },
          outcome: "unexpected",
          results: [
            {
              attachments: [
                { name: "trace", path: "data/trace.zip" },
                { name: "error-context", path: "data/first.md" },
              ],
            },
            { attachments: [{ name: "error-context", path: "data/retry.md" }] },
          ],
        },
        {
          title: "재시도로 통과한다",
          path: [],
          projectName: "chromium",
          location: { file: "board-map.spec.ts", line: 10 },
          outcome: "flaky",
          results: [],
        },
      ],
    },
  ],
};

describe("failedTestsFrom", () => {
  it("재시도로 통과한 테스트는 빼고 실패한 테스트만 고른다", () => {
    expect(failedTestsFrom(report)).toEqual([
      {
        file: "board-map.spec.ts",
        line: 1684,
        project: "iphone-15",
        titlePath: ["판정 보드", "펼치고 접어도 위치를 유지한다"],
        errorContext: "data/first.md",
      },
    ]);
  });

  it("리포트의 실패 수와 읽은 실패 수가 다르면 E2E 실패 없음으로 넘기지 않고 멈춘다", () => {
    expect(() => failedTestsFrom({ ...report, stats: { unexpected: 3 } })).toThrow(
      "리포트의 실패 수(3)와 읽은 실패 수(1)가 다릅니다.",
    );
  });
});

describe("failureSplitFrom", () => {
  it("브라우저와 줄 번호가 달라도 파일과 제목이 같으면 dev에서도 실패한 테스트로 나눈다", () => {
    const sharedFailure = createFailure({ project: "iphone-15", line: 1463 });
    const ownFailure = createFailure({ file: "route-select.spec.ts", titlePath: ["결과 없음을 알린다"] });
    const devFailure = createFailure({ project: "firefox", line: 1470 });

    expect(failureSplitFrom([sharedFailure, ownFailure], [devFailure])).toEqual({
      sharedWithDev: [sharedFailure],
      branchOnly: [ownFailure],
    });
  });

  it("describe가 다르면 이 브랜치에서만 실패한 테스트로 본다", () => {
    const failure = createFailure({ titlePath: ["지도", "확대한다"] });
    const devFailure = createFailure({ titlePath: ["목록", "확대한다"] });

    expect(failureSplitFrom([failure], [devFailure])).toEqual({ sharedWithDev: [], branchOnly: [failure] });
  });
});

describe("testLabelOf", () => {
  it("CI 로그와 같은 모양으로 프로젝트, 위치, 제목을 보여 준다", () => {
    expect(testLabelOf(createFailure({ titlePath: ["판정 보드", "확대한다"] }))).toBe(
      "[chromium] e2e/board-map.spec.ts:1463 › 판정 보드 › 확대한다",
    );
  });
});
