import { describe, expect, it } from "@jest/globals";
import type { FailedTest } from "./failures.ts";
import { testScopeFor } from "./worktree.ts";

function createFailure(overrides: Partial<FailedTest>): FailedTest {
  return {
    file: "board-map.spec.ts",
    line: 1463,
    project: "chromium",
    titlePath: ["확대한다"],
    errorContext: null,
    ...overrides,
  };
}

describe("testScopeFor", () => {
  it("CI에서 실패한 프로젝트와 파일만 한 번씩 고른다", () => {
    const failures = [
      createFailure({ project: "iphone-15" }),
      createFailure({ project: "iphone-15", line: 1684 }),
      createFailure({ project: "chromium", file: "auth.spec.ts" }),
    ];

    expect(testScopeFor(failures)).toEqual({
      projects: ["iphone-15", "chromium"],
      testMatch: ["**/board-map.spec.ts", "**/auth.spec.ts"],
    });
  });
});
