import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";
import process from "node:process";
import { setTimeout as sleep } from "node:timers/promises";
import type { FailedTest } from "./failures.ts";

export type Agent = "claude" | "codex";
export type Verdict = "테스트 수정" | "제품 버그 의심" | "판단 못 함";

export interface RunSummary {
  runId: number;
  url: string;
  branch: string;
  sha: string;
  failures: FailedTest[];
}

export interface HealerReport {
  agent: Agent;
  verdicts: [Verdict, ...Verdict[]];
  seconds: number;
  changedFiles: string[];
  warnings: string[];
}

export interface DevOverlap {
  devRunId: number;
  sharedFailures: FailedTest[];
}

export type WatchResult =
  | (RunSummary & { outcome: "not-e2e" })
  | (RunSummary & { outcome: "dev-problem" } & DevOverlap)
  | (RunSummary & { outcome: "healed"; healer: HealerReport; devOverlap: DevOverlap | null });

export const stateRoot = path.join(
  process.env.XDG_STATE_HOME ?? path.join(homedir(), ".local", "state"),
  "salmonbus-ci-watch",
);

const lockDir = path.join(stateRoot, "healer.lock");
const lockWaitMs = 60 * 60_000;

export function runDirFor(runId: number) {
  return path.join(stateRoot, String(runId));
}

const agentNames: Record<Agent, true> = { claude: true, codex: true };
const verdictNames: Record<Verdict, true> = { "테스트 수정": true, "제품 버그 의심": true, "판단 못 함": true };

function isAgent(value: unknown): value is Agent {
  return typeof value === "string" && Object.hasOwn(agentNames, value);
}

function isVerdict(value: unknown): value is Verdict {
  return typeof value === "string" && Object.hasOwn(verdictNames, value);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isString(value: unknown): value is string {
  return typeof value === "string";
}

function numberFrom(value: unknown, fieldPath: string): number {
  if (typeof value !== "number") throw new Error(`${fieldPath} 값이 숫자가 아닙니다.`);
  return value;
}

function stringFrom(value: unknown, fieldPath: string): string {
  if (!isString(value)) throw new Error(`${fieldPath} 값이 문자열이 아닙니다.`);
  return value;
}

function stringsFrom(value: unknown, fieldPath: string): string[] {
  if (!Array.isArray(value) || !value.every(isString)) throw new Error(`${fieldPath} 값이 문자열 목록이 아닙니다.`);
  return value;
}

function failedTestFrom(value: unknown, fieldPath: string): FailedTest {
  if (!isRecord(value)) throw new Error(`${fieldPath} 값이 객체가 아닙니다.`);
  return {
    file: stringFrom(value.file, `${fieldPath}.file`),
    line: numberFrom(value.line, `${fieldPath}.line`),
    project: stringFrom(value.project, `${fieldPath}.project`),
    titlePath: stringsFrom(value.titlePath, `${fieldPath}.titlePath`),
    errorContext: value.errorContext === null ? null : stringFrom(value.errorContext, `${fieldPath}.errorContext`),
  };
}

function failuresFrom(value: unknown, fieldPath: string): FailedTest[] {
  if (!Array.isArray(value)) throw new Error(`${fieldPath} 값이 실패 목록이 아닙니다.`);
  return value.map((item, index) => failedTestFrom(item, `${fieldPath}[${index}]`));
}

function devOverlapFrom(fields: Record<string, unknown>, pathPrefix: string): DevOverlap {
  return {
    devRunId: numberFrom(fields.devRunId, `${pathPrefix}devRunId`),
    sharedFailures: failuresFrom(fields.sharedFailures, `${pathPrefix}sharedFailures`),
  };
}

function healerReportFrom(value: unknown): HealerReport {
  if (!isRecord(value)) throw new Error("healer 값이 객체가 아닙니다.");
  const { agent, verdicts } = value;
  if (!isAgent(agent)) throw new Error("healer.agent 값이 claude, codex 중 하나가 아닙니다.");
  const [firstVerdict, ...otherVerdicts] = Array.isArray(verdicts) && verdicts.every(isVerdict) ? verdicts : [];
  if (!firstVerdict) throw new Error("healer.verdicts 값이 판정 목록이 아닙니다.");
  return {
    agent,
    verdicts: [firstVerdict, ...otherVerdicts],
    seconds: numberFrom(value.seconds, "healer.seconds"),
    changedFiles: stringsFrom(value.changedFiles, "healer.changedFiles"),
    warnings: stringsFrom(value.warnings, "healer.warnings"),
  };
}

export function resultFrom(json: unknown): WatchResult {
  if (!isRecord(json)) throw new Error("결과가 객체가 아닙니다.");
  const summary: RunSummary = {
    runId: numberFrom(json.runId, "runId"),
    url: stringFrom(json.url, "url"),
    branch: stringFrom(json.branch, "branch"),
    sha: stringFrom(json.sha, "sha"),
    failures: failuresFrom(json.failures, "failures"),
  };
  switch (json.outcome) {
    case "not-e2e":
      return { ...summary, outcome: "not-e2e" };
    case "dev-problem":
      return { ...summary, outcome: "dev-problem", ...devOverlapFrom(json, "") };
    case "healed": {
      const healer = healerReportFrom(json.healer);
      const { devOverlap } = json;
      if (devOverlap === null) return { ...summary, outcome: "healed", healer, devOverlap: null };
      if (!isRecord(devOverlap)) throw new Error("devOverlap 값이 null이나 객체가 아닙니다.");
      return { ...summary, outcome: "healed", healer, devOverlap: devOverlapFrom(devOverlap, "devOverlap.") };
    }
    default:
      throw new Error("outcome 값이 not-e2e, dev-problem, healed 중 하나가 아닙니다.");
  }
}

export class StoredResultError extends Error {}

export function loadResult(runId: number): WatchResult | null {
  const runDir = runDirFor(runId);
  const file = path.join(runDir, "result.json");
  if (!existsSync(file)) return null;
  const text = readFileSync(file, "utf8");
  try {
    return resultFrom(JSON.parse(text));
  } catch (error) {
    if (!(error instanceof Error)) throw error;
    const reason = error instanceof SyntaxError ? "JSON으로 읽을 수 없습니다." : error.message;
    throw new StoredResultError(
      `실행 ${runId}의 저장된 결과(${file})가 지금 형식과 맞지 않습니다: ${reason} ${runDir} 폴더를 지운 뒤 pnpm ci:watch --run ${runId} 명령으로 다시 판정해 주세요.`,
    );
  }
}

export function saveResult(result: WatchResult, summary: string) {
  const runDir = runDirFor(result.runId);
  mkdirSync(runDir, { recursive: true });
  writeFileSync(path.join(runDir, "summary.md"), summary);
  writeFileSync(path.join(runDir, "result.json"), JSON.stringify(result, null, 2) + "\n");
}

function isLockHolderAlive() {
  try {
    process.kill(Number(readFileSync(path.join(lockDir, "pid"), "utf8")), 0);
    return true;
  } catch (error) {
    // pid를 쓰기 전에 읽었으면 막 잠근 감시가 있는 것이다.
    return (error as NodeJS.ErrnoException).code !== "ESRCH";
  }
}

// healer는 고정 포트와 고정 임시 워크트리를 쓰므로 한 번에 하나만 돈다.
export async function withHealerLock<T>(task: () => Promise<T>): Promise<T> {
  mkdirSync(stateRoot, { recursive: true });
  const deadline = Date.now() + lockWaitMs;
  for (;;) {
    try {
      mkdirSync(lockDir);
      writeFileSync(path.join(lockDir, "pid"), String(process.pid));
      break;
    } catch {
      if (!isLockHolderAlive()) {
        rmSync(lockDir, { recursive: true, force: true });
        continue;
      }
      if (Date.now() > deadline)
        throw new Error(`다른 healer가 60분 넘게 끝나지 않습니다. ${lockDir}를 확인해 주세요.`);
      await sleep(15_000);
    }
  }
  try {
    return await task();
  } finally {
    rmSync(lockDir, { recursive: true, force: true });
  }
}
