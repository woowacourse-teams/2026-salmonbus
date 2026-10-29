import { execFileSync } from "node:child_process";
import { rmSync } from "node:fs";
import path from "node:path";
import { failedTestsFrom, loadReport, type FailedTest } from "./failures.ts";

const workflow = "FRONTEND-CI";
const artifactName = "playwright-results";
const runFields = "databaseId,status,conclusion,event,headBranch,headSha,url";

export interface CiRun {
  databaseId: number;
  status: string;
  conclusion: string;
  event: string;
  headBranch: string;
  headSha: string;
  url: string;
}

// 기다려도 저절로 풀리지 않는 실패. 다시 시도하지 않고 바로 알린다.
export class GhUnavailableError extends Error {}

function gh(args: string[]) {
  try {
    return execFileSync("gh", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  } catch (error) {
    const { code, status } = error as NodeJS.ErrnoException & { status?: number };
    if (code === "ENOENT") throw new GhUnavailableError("gh 명령을 찾지 못했어요.");
    // gh는 로그인이 안 돼 있으면 4로 끝난다.
    if (status === 4) throw new GhUnavailableError("gh 로그인이 필요해요: gh auth login");
    throw error;
  }
}

// 커밋만으로 찾으면 dev가 이미 돌린 커밋을 새 브랜치로 푸시했을 때 dev의 실행이 나온다.
export function fetchRunFor(sha: string, branch: string): CiRun | undefined {
  const runs: CiRun[] = JSON.parse(
    gh([
      "run",
      "list",
      "--workflow",
      workflow,
      "--branch",
      branch,
      "--commit",
      sha,
      "--limit",
      "1",
      "--json",
      runFields,
    ]),
  );
  return runs[0];
}

export function fetchRun(runId: number): CiRun {
  return JSON.parse(gh(["run", "view", String(runId), "--json", runFields]));
}

export function fetchLatestDevPushRun(): CiRun | undefined {
  const runs: CiRun[] = JSON.parse(
    gh([
      "run",
      "list",
      "--workflow",
      workflow,
      "--branch",
      "dev",
      "--event",
      "push",
      "--status",
      "completed",
      "--limit",
      "1",
      "--json",
      runFields,
    ]),
  );
  return runs[0];
}

export type RunEnding = { kind: "quiet" } | { kind: "failure" } | { kind: "stopped"; title: string };

// job 시간 제한에 걸린 실행도 conclusion은 cancelled로 와서, 새 푸시에 밀린 취소와는 job annotation으로 가른다.
export function runEndingFor(conclusion: string, cancelMessages: string[]): RunEnding {
  if (conclusion === "success") return { kind: "quiet" };
  if (conclusion === "failure") return { kind: "failure" };
  if (cancelMessages.some((message) => message.includes("higher priority waiting request"))) return { kind: "quiet" };
  const timedOut =
    conclusion === "timed_out" ||
    cancelMessages.some((message) => message.includes("exceeded the maximum execution time"));
  return { kind: "stopped", title: timedOut ? "CI 시간 초과" : `CI ${conclusion}` };
}

export function fetchCancelMessages(runId: number) {
  const jobIds = gh(["api", `repos/{owner}/{repo}/actions/runs/${runId}/jobs`, "--jq", ".jobs[].id"])
    .split("\n")
    .filter(Boolean);
  return jobIds.flatMap((jobId) =>
    gh(["api", `repos/{owner}/{repo}/check-runs/${jobId}/annotations`, "--jq", ".[].message"])
      .split("\n")
      .filter(Boolean),
  );
}

// E2E 단계까지 가지 못해 실패 기록이 없으면 null을 돌려준다.
export function fetchFailures(runId: number, directory: string): FailedTest[] | null {
  const expired = gh([
    "api",
    `repos/{owner}/{repo}/actions/runs/${runId}/artifacts`,
    "--jq",
    `.artifacts[] | select(.name == "${artifactName}") | .expired`,
  ]).trim();
  if (expired === "") return null;
  if (expired.includes("true")) throw new Error(`실행 ${runId}의 ${artifactName} 기록이 만료됐습니다.`);

  rmSync(directory, { recursive: true, force: true });
  gh(["run", "download", String(runId), "-n", artifactName, "-D", directory]);
  return failedTestsFrom(loadReport(path.join(directory, "playwright-report")));
}
