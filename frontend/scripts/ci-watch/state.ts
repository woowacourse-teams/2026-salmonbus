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

export function loadResult(runId: number): WatchResult | null {
  const file = path.join(runDirFor(runId), "result.json");
  return existsSync(file) ? JSON.parse(readFileSync(file, "utf8")) : null;
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
