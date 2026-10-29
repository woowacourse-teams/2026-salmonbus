import { execFileSync } from "node:child_process";
import { closeSync, copyFileSync, existsSync, mkdirSync, openSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import type { FailedTest } from "./failures.ts";
import type { Agent } from "./state.ts";

export const healerPort = 5593;
export const configFile = "playwright.ci-watch.config.ts";

// Codex는 config.toml에 적힌 폴더만 신뢰하므로 임시 워크트리 경로를 고정한다.
export const worktreeDir = path.join(realpathSync(tmpdir()), "salmonbus-ci-watch");
export const frontendDir = path.join(worktreeDir, "frontend");

const definitionsFor: Record<Agent, string[]> = {
  claude: [".claude/agents/playwright-test-healer.md", "AGENTS.md"],
  codex: [".codex/agents/playwright_test_healer.toml", "AGENTS.md"],
};

// `git rev-parse --local-env-vars` 가운데 git이 어느 저장소, 워크트리, index를 볼지 정하는 변수.
const gitLocationVariables = new Set([
  "GIT_DIR",
  "GIT_WORK_TREE",
  "GIT_INDEX_FILE",
  "GIT_COMMON_DIR",
  "GIT_OBJECT_DIRECTORY",
  "GIT_ALTERNATE_OBJECT_DIRECTORIES",
  "GIT_PREFIX",
]);

// 연결된 워크트리에서 푸시하면 hook에 GIT_DIR이 들어온다. 그대로 두면 임시 워크트리에서 돌린 git이 푸시한 워크트리의 저장소와 index를 본다.
export function envWithoutGitLocation(env: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  return Object.fromEntries(Object.entries(env).filter(([name]) => !gitLocationVariables.has(name)));
}

function git(cwd: string, args: string[]) {
  return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

// healer는 origin/dev와 비교해 브랜치가 바꾼 것을 찾으므로 비교 기준을 최신으로 맞춘다.
export function fetchCommits(repoDir: string, sha: string) {
  git(repoDir, ["fetch", "--quiet", "origin", "dev"]);
  try {
    git(repoDir, ["cat-file", "-e", `${sha}^{commit}`]);
  } catch {
    git(repoDir, ["fetch", "--quiet", "origin", sha]);
  }
}

export function missingDefinitionsFor(agent: Agent, repoDir: string, sha: string, healerFrom?: string) {
  return definitionsFor[agent].filter((file) => {
    if (healerFrom && existsSync(path.join(healerFrom, file))) return false;
    try {
      git(repoDir, ["cat-file", "-e", `${sha}:frontend/${file}`]);
      return false;
    } catch {
      return true;
    }
  });
}

// CI에서 실패한 프로젝트와 파일만 다시 돌린다.
export function testScopeFor(failures: FailedTest[]) {
  return {
    projects: [...new Set(failures.map((test) => test.project))],
    testMatch: [...new Set(failures.map((test) => `**/${test.file}`))],
  };
}

function playwrightConfigFor(failures: FailedTest[]) {
  return `import { defineConfig } from "@playwright/test";
import base from "./playwright.config";

// ci-watch가 만든 임시 설정. 다른 서버와 겹치지 않는 포트에서 CI가 실패한 범위만 돌린다.
const scope = ${JSON.stringify(testScopeFor(failures))};
const port = ${healerPort};
const baseURL = \`http://127.0.0.1:\${port}\`;
const basePort = new URL(String(base.use?.baseURL)).port;
const server = Array.isArray(base.webServer) ? base.webServer[0] : base.webServer;

export default defineConfig({
  ...base,
  testMatch: scope.testMatch,
  use: { ...base.use, baseURL },
  projects: (base.projects ?? []).filter((project) => scope.projects.includes(project.name ?? "")),
  webServer: {
    ...server,
    command: server.command.replaceAll(basePort, String(port)),
    url: baseURL,
    reuseExistingServer: false,
    env: { ...server.env, API_PROXY_TARGET: "http://127.0.0.1:9" },
  },
});
`;
}

// 스크립트가 넣은 파일 목록을 돌려준다. healer가 바꾼 것과 구분할 때 쓴다.
export function createHealerWorktree(options: {
  repoDir: string;
  sha: string;
  agent: Agent;
  failures: FailedTest[];
  healerFrom?: string;
  setupLog: string;
}) {
  removeHealerWorktree(options.repoDir);
  git(options.repoDir, ["worktree", "add", "--force", "--detach", worktreeDir, options.sha]);

  const setupLog = openSync(options.setupLog, "a");
  try {
    execFileSync("pnpm", ["install", "--frozen-lockfile", "--prefer-offline"], {
      cwd: frontendDir,
      stdio: ["ignore", setupLog, setupLog],
    });
  } catch {
    throw new Error(`임시 워크트리의 pnpm install이 실패했습니다. ${options.setupLog}를 확인해 주세요.`);
  } finally {
    closeSync(setupLog);
  }

  writeFileSync(path.join(frontendDir, configFile), playwrightConfigFor(options.failures));
  const injected = [configFile];
  for (const file of definitionsFor[options.agent]) {
    const target = path.join(frontendDir, file);
    if (existsSync(target) || !options.healerFrom) continue;
    mkdirSync(path.dirname(target), { recursive: true });
    copyFileSync(path.join(options.healerFrom, file), target);
    injected.push(file);
  }
  return injected.map((file) => `frontend/${file}`);
}

export function removeHealerWorktree(repoDir: string) {
  if (!existsSync(worktreeDir)) return;
  try {
    git(repoDir, ["worktree", "remove", "--force", worktreeDir]);
  } catch {
    rmSync(worktreeDir, { recursive: true, force: true });
  }
}

// healer가 새로 만든 파일도 diff에 나오게 intent-to-add로 올린다. 임시 워크트리의 index만 바뀐다.
export function collectChanges(injected: string[]) {
  const created = git(worktreeDir, ["ls-files", "--others", "--exclude-standard"])
    .split("\n")
    .filter((file) => file && !injected.includes(file));
  if (created.length > 0) git(worktreeDir, ["add", "--intent-to-add", "--", ...created]);
  return {
    diff: git(worktreeDir, ["diff"]),
    changedFiles: git(worktreeDir, ["diff", "--name-only"]).split("\n").filter(Boolean),
  };
}
