import { describe, expect, it } from "@jest/globals";
import type { FailedTest } from "./failures.ts";
import { envWithoutGitLocation, testScopeFor } from "./worktree.ts";

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

describe("envWithoutGitLocation", () => {
  it("연결된 워크트리의 pre-push hook 환경에서 저장소 위치 변수만 빼고 나머지는 그대로 둔다", () => {
    const hookEnv = {
      GIT_DIR: "/repo/.git/worktrees/SAL-286",
      GIT_PREFIX: "",
      GIT_EDITOR: "true",
      GIT_EXEC_PATH: "/usr/libexec/git-core",
      GIT_TERMINAL_PROMPT: "0",
      GIT_CONFIG_COUNT: "1",
      GIT_CONFIG_KEY_0: "credential.interactive",
      GIT_CONFIG_VALUE_0: "false",
      PATH: "/usr/bin",
    };

    expect(envWithoutGitLocation(hookEnv)).toEqual({
      GIT_EDITOR: "true",
      GIT_EXEC_PATH: "/usr/libexec/git-core",
      GIT_TERMINAL_PROMPT: "0",
      GIT_CONFIG_COUNT: "1",
      GIT_CONFIG_KEY_0: "credential.interactive",
      GIT_CONFIG_VALUE_0: "false",
      PATH: "/usr/bin",
    });
  });

  it("작업 트리, index, 공용 저장소 위치를 정하는 변수도 뺀다", () => {
    const env = {
      GIT_WORK_TREE: "/repo",
      GIT_INDEX_FILE: "/repo/.git/index",
      GIT_COMMON_DIR: "/repo/.git",
      GIT_OBJECT_DIRECTORY: "/repo/.git/objects",
      GIT_ALTERNATE_OBJECT_DIRECTORIES: "/other/objects",
      HOME: "/home/me",
    };

    expect(envWithoutGitLocation(env)).toEqual({ HOME: "/home/me" });
  });
});
