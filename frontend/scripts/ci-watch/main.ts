import { execFileSync } from "node:child_process";
import { appendFileSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";
import { parseArgs } from "node:util";
import { fetchFailures, fetchLatestDevPushRun, fetchRun, fetchRunFor, GhUnavailableError, type CiRun } from "./ci.ts";
import { sharedFailuresFrom, type FailedTest } from "./failures.ts";
import { promptFor, runHealer, verdictFrom, warningsFor, whyHealerCannotRun } from "./healer.ts";
import { markOrcaCard, noticeFor, notify, summaryFor, type Notice } from "./notify.ts";
import {
  loadResult,
  runDirFor,
  saveResult,
  stateRoot,
  withHealerLock,
  type Agent,
  type RunSummary,
  type WatchResult,
} from "./state.ts";
import {
  collectChanges,
  createHealerWorktree,
  fetchCommits,
  missingDefinitionsFor,
  removeHealerWorktree,
} from "./worktree.ts";

const usage = `사용법
  pnpm ci:watch --run <run id> [--agent claude|codex] [--healer-from <frontend 폴더>]
  node scripts/ci-watch/main.ts --sha <커밋>   (pre-push hook이 부른다)`;

// PR이 없거나 paths 필터에 걸려 실행이 생기지 않으면 이 시간 뒤 조용히 끝낸다.
const runAppearMs = 10 * 60_000;
const runFinishMs = 90 * 60_000;

const { values } = parseArgs({
  options: {
    sha: { type: "string" },
    run: { type: "string" },
    agent: { type: "string" },
    "healer-from": { type: "string" },
  },
});
const healerFrom = values["healer-from"] && path.resolve(values["healer-from"]);
const worktreeDir = execFileSync("git", ["rev-parse", "--show-toplevel"], { encoding: "utf8" }).trim();
const logFile = values.sha ? path.join(stateRoot, "logs", `${values.sha}.log`) : null;

function log(line: string) {
  if (!logFile) {
    console.log(line);
    return;
  }
  mkdirSync(path.dirname(logFile), { recursive: true });
  appendFileSync(logFile, `${new Date().toISOString()} ${line}\n`);
}

function configuredHealer() {
  try {
    return execFileSync("git", ["config", "--get", "salmonbus.e2eHealer"], { encoding: "utf8" }).trim();
  } catch {
    return undefined;
  }
}

function healerChoiceFrom(value?: string): Agent | "off" {
  const choice = value || "claude";
  if (choice === "claude" || choice === "codex" || choice === "off") return choice;
  throw new Error(`healer 값 ${choice}를 알 수 없습니다. claude, codex, off 중 하나로 정해 주세요.`);
}

async function poll<T>(find: () => T | undefined, intervalMs: number, timeoutMs: number) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      const found = find();
      if (found) return found;
    } catch (error) {
      if (error instanceof GhUnavailableError) throw error;
      log(`GitHub 조회 실패, 다시 시도합니다: ${(error as Error).message.split("\n")[0]}`);
    }
    if (Date.now() + intervalMs > deadline) return undefined;
    await sleep(intervalMs);
  }
}

function announce(notice: Notice, runId: number) {
  log(`${notice.title}. ${notice.message}`);
  notify(notice);
  const previousComment = markOrcaCard(worktreeDir, notice.title);
  if (previousComment !== null) {
    writeFileSync(path.join(runDirFor(runId), "orca-previous-comment.txt"), previousComment + "\n");
  }
}

function finish(result: WatchResult, healerMessage?: string) {
  const summary = summaryFor(result, healerMessage);
  saveResult(result, summary);
  announce(noticeFor(result), result.runId);
  if (!logFile) console.log("\n" + summary);
}

function showSaved(result: WatchResult) {
  log(`실행 ${result.runId}는 이미 판정했어요. healer를 다시 부르지 않습니다.`);
  notify(noticeFor(result));
  if (!logFile) console.log("\n" + readFileSync(path.join(runDirFor(result.runId), "summary.md"), "utf8"));
}

function fetchSharedDevFailures(failures: FailedTest[], runDir: string) {
  const devRun = fetchLatestDevPushRun();
  if (devRun?.conclusion !== "failure") return null;
  const devArtifactDir = path.join(runDir, "dev-artifact");
  try {
    const sharedFailures = sharedFailuresFrom(failures, fetchFailures(devRun.databaseId, devArtifactDir) ?? []);
    return sharedFailures.length > 0 ? { devRunId: devRun.databaseId, sharedFailures } : null;
  } finally {
    rmSync(devArtifactDir, { recursive: true, force: true });
  }
}

async function heal(runSummary: RunSummary, agent: Agent, artifactDir: string) {
  const runDir = runDirFor(runSummary.runId);
  log(`${agent} healer를 부릅니다.`);
  try {
    const injected = createHealerWorktree({
      repoDir: worktreeDir,
      sha: runSummary.sha,
      agent,
      failures: runSummary.failures,
      ...(healerFrom ? { healerFrom } : {}),
      setupLog: path.join(runDir, "setup.log"),
    });
    const prompt = promptFor(agent, runSummary.failures, path.join(artifactDir, "playwright-report"));
    const { finalMessage, seconds, timedOut } = await runHealer(agent, prompt, artifactDir, runDir);
    const { diff, changedFiles } = collectChanges(injected);
    writeFileSync(path.join(runDir, "healer.diff"), diff);

    const verdict = verdictFrom(finalMessage);
    const warnings = warningsFor(verdict, changedFiles);
    if (timedOut) warnings.unshift("healer가 30분 안에 끝나지 않아 멈췄어요.");
    finish(
      { ...runSummary, outcome: "healed", healer: { agent, verdict, seconds, changedFiles, warnings } },
      finalMessage,
    );
  } finally {
    removeHealerWorktree(worktreeDir);
  }
}

async function handleFailure(run: CiRun, agent: Agent) {
  const saved = loadResult(run.databaseId);
  if (saved) return showSaved(saved);

  const runDir = runDirFor(run.databaseId);
  // 사람이 판정을 확인할 때 trace를 열 수 있게 CI 실패 기록은 지우지 않는다.
  const artifactDir = path.join(runDir, "artifact");
  mkdirSync(runDir, { recursive: true });
  const failures = fetchFailures(run.databaseId, artifactDir) ?? [];
  const runSummary = { runId: run.databaseId, url: run.url, branch: run.headBranch, sha: run.headSha, failures };
  if (failures.length === 0) return finish({ ...runSummary, outcome: "not-e2e" });

  const devProblem = run.event === "push" ? null : fetchSharedDevFailures(failures, runDir);
  if (devProblem) return finish({ ...runSummary, outcome: "dev-problem", ...devProblem });

  const blocked = await whyHealerCannotRun(agent);
  fetchCommits(worktreeDir, run.headSha);
  const missing = missingDefinitionsFor(agent, worktreeDir, run.headSha, healerFrom);
  const reason = blocked ?? (missing.length > 0 ? `healer 정의가 이 커밋에 없어요: ${missing.join(", ")}` : null);
  if (reason) {
    announce(
      { title: `E2E: 실패 ${failures.length}개, healer를 부르지 못함`, subtitle: run.headBranch, message: reason },
      run.databaseId,
    );
    return;
  }
  await heal(runSummary, agent, artifactDir);
}

async function main() {
  const agent = healerChoiceFrom(values.agent ?? configuredHealer());
  if (agent === "off") {
    log("healer가 off라서 CI를 지켜보지 않습니다.");
    return;
  }

  let run: CiRun | undefined;
  if (values.run) {
    const saved = loadResult(Number(values.run));
    if (saved) return showSaved(saved);
    run = fetchRun(Number(values.run));
  } else if (values.sha) {
    const sha = values.sha;
    run = await poll(() => fetchRunFor(sha), 20_000, runAppearMs);
    if (!run) return log(`${sha}의 FRONTEND-CI 실행이 10분 동안 생기지 않아 끝냅니다.`);
  } else {
    console.error(usage);
    process.exitCode = 1;
    return;
  }

  log(`실행 ${run.databaseId}(${run.headBranch})를 지켜봅니다: ${run.url}`);
  const runId = run.databaseId;
  const completed = await poll(
    () => {
      const current = fetchRun(runId);
      return current.status === "completed" ? current : undefined;
    },
    30_000,
    runFinishMs,
  );
  if (!completed) return log(`실행 ${runId}가 90분 안에 끝나지 않아 감시를 멈춥니다.`);
  if (completed.conclusion !== "failure") {
    return log(`실행 ${runId}가 ${completed.conclusion}로 끝나 healer를 부르지 않습니다.`);
  }
  await withHealerLock(() => handleFailure(completed, agent));
}

try {
  await main();
} catch (error) {
  // gh를 설치하거나 로그인하면 풀리는 실패는 메시지에 할 일이 다 들어 있다.
  log(error instanceof GhUnavailableError ? error.message : ((error as Error).stack ?? String(error)));
  notify({ title: "E2E: 감시가 멈췄어요", subtitle: path.basename(worktreeDir), message: (error as Error).message });
  process.exitCode = 1;
}
