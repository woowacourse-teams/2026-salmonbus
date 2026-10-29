import { afterAll, beforeAll, describe, expect, it } from "@jest/globals";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import process from "node:process";
import type { FailedTest } from "./failures.ts";
import type { HealerReport, WatchResult } from "./state.ts";

const stateHome = mkdtempSync(path.join(tmpdir(), "ci-watch-state-"));
let state: typeof import("./state.ts");

// stateRoot는 state.ts를 불러올 때 정해지므로, 실제 기록 대신 임시 폴더를 보도록 먼저 바꾸고 불러온다.
beforeAll(async () => {
  process.env.XDG_STATE_HOME = stateHome;
  state = await import("./state.ts");
  if (!state.stateRoot.startsWith(stateHome)) throw new Error(`테스트가 실제 기록 폴더 ${state.stateRoot}를 봅니다.`);
});

afterAll(() => rmSync(stateHome, { recursive: true, force: true }));

const failure: FailedTest = {
  file: "route-select.spec.ts",
  line: 105,
  project: "chromium",
  titlePath: ["결과 없음을 알린다"],
  errorContext: "data/5320e471162df2e7ffcd7662ec008cc6cb9e758f.md",
};

const summary = {
  runId: 40000000001,
  url: "https://github.com/woowacourse-teams/2026-salmonbus/actions/runs/40000000001",
  branch: "fe/test/SAL-286-push-check-2",
  sha: "ab603cea599de3a5fd7a66fce44d4d716532b6ad",
  failures: [failure, { ...failure, project: "iphone-15", errorContext: null }],
};

const healer: HealerReport = {
  agent: "claude",
  verdicts: ["테스트 수정"],
  seconds: 38,
  changedFiles: ["frontend/e2e/route-select.spec.ts"],
  warnings: [],
};

const devOverlap = { devRunId: 40000000000, sharedFailures: [failure] };

// healer 판정을 하나만 적던 때 저장된 실제 기록의 모양이다.
const oldHealedRecord = {
  ...summary,
  outcome: "healed",
  healer: {
    agent: "claude",
    verdict: "테스트 수정",
    seconds: 38,
    changedFiles: ["frontend/e2e/route-select.spec.ts"],
    warnings: [],
  },
};

function storeRecord(runId: number, text: string) {
  mkdirSync(state.runDirFor(runId), { recursive: true });
  writeFileSync(path.join(state.runDirFor(runId), "result.json"), text);
}

describe("loadResult", () => {
  it.each<[string, WatchResult]>([
    ["다른 CI 단계 실패", { ...summary, runId: 1, outcome: "not-e2e" }],
    ["dev 쪽 문제", { ...summary, runId: 2, outcome: "dev-problem", ...devOverlap }],
    ["healer 판정", { ...summary, runId: 3, outcome: "healed", healer, devOverlap: null }],
    [
      "dev와 일부 겹친 healer 판정",
      {
        ...summary,
        runId: 4,
        outcome: "healed",
        healer: { ...healer, verdicts: ["제품 버그 의심", "테스트 수정"] },
        devOverlap,
      },
    ],
  ])("지금 형식으로 저장한 %s 결과를 그대로 읽는다", (_, result) => {
    state.saveResult(result, "요약");

    expect(state.loadResult(result.runId)).toEqual(result);
  });

  it("저장된 결과가 없으면 null을 돌려준다", () => {
    expect(state.loadResult(5)).toBeNull();
  });

  it("옛 형식으로 저장된 결과는 틀린 값과 다시 판정하는 방법을 알리며 멈춘다", () => {
    storeRecord(summary.runId, JSON.stringify(oldHealedRecord));
    const runDir = state.runDirFor(summary.runId);
    const load = () => state.loadResult(summary.runId);

    expect(load).toThrow(state.StoredResultError);
    expect(load).toThrow(path.join(runDir, "result.json"));
    expect(load).toThrow("healer.verdicts");
    expect(load).toThrow(`${runDir} 폴더를 지운 뒤 pnpm ci:watch --run ${summary.runId}`);
  });

  it("JSON으로 읽을 수 없는 결과도 다시 판정하라고 알리며 멈춘다", () => {
    storeRecord(6, '{"runId": 6,');

    expect(() => state.loadResult(6)).toThrow(state.StoredResultError);
  });
});

describe("resultFrom", () => {
  it("판정을 하나만 적던 옛 healed 기록은 healer.verdicts를 짚어 거절한다", () => {
    expect(() => state.resultFrom(oldHealedRecord)).toThrow("healer.verdicts");
  });

  it("devOverlap이 빠진 healed 기록을 거절한다", () => {
    expect(() => state.resultFrom({ ...summary, outcome: "healed", healer })).toThrow("devOverlap");
  });

  it("모르는 outcome을 거절한다", () => {
    expect(() => state.resultFrom({ ...summary, outcome: "passed" })).toThrow("outcome");
  });
});
