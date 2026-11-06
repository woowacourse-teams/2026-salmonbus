import { describe, expect, it } from "@jest/globals";
import {
  comparisonFrom,
  comparisonOf,
  inventoryOf,
  runStatsOf,
  summaryMarkdownOf,
  type Inventory,
  type Loaded,
  type PlaywrightJsonReport,
} from "./summary.ts";

function unwrapped<T>(result: Loaded<T>): T {
  if (!result.ok) throw new Error(result.reason);
  return result.value;
}

function inventory(list: PlaywrightJsonReport): Inventory {
  return unwrapped(inventoryOf(list));
}

function brokenList(): PlaywrightJsonReport {
  return {
    stats: { duration: 300 },
    suites: [],
    errors: [
      {
        message: "\u001b[31mError: Cannot find module './nope'\u001b[39m\nRequire stack:\n- broken.spec.ts",
      },
      { message: "second error" },
    ],
  };
}

function attempt(startTime: string, duration: number, status = "passed") {
  return { startTime, duration, status };
}

const runReport: PlaywrightJsonReport = {
  stats: { duration: 600_000 },
  errors: [],
  suites: [
    {
      title: "home.spec.ts",
      file: "home.spec.ts",
      specs: [
        {
          title: "홈을 연다",
          file: "home.spec.ts",
          tests: [
            {
              projectName: "chromium",
              status: "expected",
              results: [attempt("2026-11-01T00:01:00.000Z", 30_000)],
            },
            {
              projectName: "iphone-15",
              status: "flaky",
              results: [
                attempt("2026-11-01T00:01:00.000Z", 60_000, "failed"),
                attempt("2026-11-01T00:02:00.000Z", 30_000),
              ],
            },
          ],
        },
      ],
      suites: [
        {
          title: "즐겨찾기",
          file: "home.spec.ts",
          specs: [
            {
              title: "별을 누른다",
              file: "home.spec.ts",
              tests: [
                {
                  projectName: "chromium",
                  status: "unexpected",
                  results: [
                    attempt("2026-11-01T00:03:00.000Z", 60_000, "failed"),
                    attempt("2026-11-01T00:04:00.000Z", 60_000, "failed"),
                  ],
                },
                {
                  projectName: "iphone-15",
                  status: "skipped",
                  results: [attempt("2026-11-01T00:09:00.000Z", 0, "skipped")],
                },
              ],
            },
          ],
        },
      ],
    },
  ],
};

// --list 결과는 test마다 project별로 spec이 따로 나오고 results가 비어 있다.
function listOf(files: Record<string, string[]>, projects: string[]): PlaywrightJsonReport {
  return {
    stats: { duration: 400 },
    errors: [],
    suites: Object.entries(files).map(([file, titles]) => ({
      title: file,
      file,
      specs: projects.flatMap((projectName) =>
        titles.map((title) => ({
          title,
          file,
          tests: [{ projectName, status: "skipped" as const, results: [] }],
        })),
      ),
    })),
  };
}

describe("runStatsOf", () => {
  it("실행 시간은 첫 시도 시작부터 마지막 시도 끝까지이고, 건너뛴 테스트는 넣지 않는다", () => {
    expect(runStatsOf(runReport).testSpanMs).toBe(4 * 60_000);
  });

  it("Playwright 전체 시간은 stats.duration을 그대로 쓴다", () => {
    expect(runStatsOf(runReport).playwrightMs).toBe(600_000);
  });

  it("project별로 실행, 통과, flaky, 실패, 건너뜀, 재시도를 포함한 시도 수를 센다", () => {
    expect(runStatsOf(runReport).projects).toEqual([
      {
        project: "chromium",
        executed: 2,
        passed: 1,
        flaky: 0,
        failed: 1,
        skipped: 0,
        attempts: 3,
        testMs: 150_000,
        flakyNames: [],
      },
      {
        project: "iphone-15",
        executed: 1,
        passed: 0,
        flaky: 1,
        failed: 0,
        skipped: 1,
        attempts: 2,
        testMs: 90_000,
        flakyNames: ["home.spec.ts › 홈을 연다"],
      },
    ]);
  });

  it("실행한 테스트가 없으면 실행 시간을 null로 둔다", () => {
    expect(runStatsOf({ stats: { duration: 5_000 }, errors: [{}], suites: [] })).toEqual({
      playwrightMs: 5_000,
      testSpanMs: null,
      projects: [],
      errorCount: 1,
    });
  });
});

describe("inventoryOf", () => {
  it("spec 파일 수와 test() 수는 project 하나 기준이고, 실행 횟수는 test 수 x project 수다", () => {
    const counted = inventory(
      listOf({ "home.spec.ts": ["가", "나", "다"], "board.spec.ts": ["가"] }, ["chromium", "iphone-15"]),
    );

    expect(counted.fileCount).toBe(2);
    expect(counted.testNames.size).toBe(4);
    expect(counted.runCount).toBe(8);
    expect(counted.projects).toEqual(["chromium", "iphone-15"]);
  });

  it("describe 안의 test는 describe 제목까지 이어 이름을 만든다", () => {
    expect([...inventory(runReport).testNames]).toEqual([
      "home.spec.ts › 홈을 연다",
      "home.spec.ts › 즐겨찾기 › 별을 누른다",
    ]);
  });
});

describe("comparisonOf", () => {
  it("base에 없던 test()는 더한 것, head에 없는 test()는 뺀 것으로 센다", () => {
    const projects = ["chromium", "iphone-15"];
    const base = inventory(listOf({ "home.spec.ts": ["가", "나"] }, projects));
    const head = inventory(listOf({ "home.spec.ts": ["가", "다", "라"], "new.spec.ts": ["가"] }, projects));

    const comparison = comparisonOf(base, head);

    expect(comparison.added).toEqual(["home.spec.ts › 다", "home.spec.ts › 라", "new.spec.ts › 가"]);
    expect(comparison.removed).toEqual(["home.spec.ts › 나"]);
    expect(head.runCount - base.runCount).toBe(4);
  });
});

describe("summaryMarkdownOf", () => {
  const head = inventory(listOf({ "home.spec.ts": ["가", "나", "다"] }, ["chromium", "iphone-15"]));
  const base = inventory(listOf({ "home.spec.ts": ["가", "나"] }, ["chromium", "iphone-15"]));

  it("실행 시간, project별 실행 횟수와 합계, 테스트 수를 쓴다", () => {
    const markdown = summaryMarkdownOf({
      run: { ok: true, value: runStatsOf(runReport) },
      head: { ok: true, value: head },
      filter: "",
    });

    expect(markdown).toContain("- 테스트 실행 시간: 4.0분");
    expect(markdown).toContain("- Playwright 전체 시간: 10.0분");
    expect(markdown).toContain("| chromium | 2 | 1 | 0 | 1 | 0 | 3 | 2.5 |");
    expect(markdown).toContain("| 합계 | 3 | 1 | 1 | 1 | 1 | 5 | 4.0 |");
    expect(markdown).toContain("- spec 파일: 1개");
    expect(markdown).toContain("- test(): 3개");
    expect(markdown).not.toContain("이 PR이 더한 E2E");
  });

  it("PR 비교가 있으면 base와 차이, 늘어난 실행 횟수, 더한 test() 이름을 쓴다", () => {
    const markdown = summaryMarkdownOf({
      run: { ok: true, value: runStatsOf(runReport) },
      head: { ok: true, value: head },
      comparison: { ok: true, value: comparisonOf(base, head) },
      filter: "--grep-invert=api-mock-cleanup.spec.ts",
    });

    expect(markdown).toContain("| test() | 2 | 3 | +1 |");
    expect(markdown).toContain("| 실행 횟수(chromium, iphone-15, 필터 적용 전 목록 기준) | 4 | 6 | +2 |");
    expect(markdown).toContain("더한 test() 1개, 뺀 test() 0개.");
    expect(markdown).toContain("- home.spec.ts › 다");
    expect(markdown).toContain("이번 실행 필터: `--grep-invert=api-mock-cleanup.spec.ts`");
  });

  it("결과나 base 목록을 읽지 못하면 이유를 남기고 나머지 절은 그대로 쓴다", () => {
    const markdown = summaryMarkdownOf({
      run: { ok: false, reason: "파일이 없다." },
      head: { ok: true, value: head },
      comparison: { ok: false, reason: "base 목록이 없다." },
      filter: "",
    });

    expect(markdown).toContain("실행 결과를 읽지 못했다: 파일이 없다.");
    expect(markdown).toContain("- test(): 3개");
    expect(markdown).toContain("비교 못 함: base 목록이 없다.");
  });
});

describe("목록에 Playwright 오류가 있을 때", () => {
  const projects = ["chromium", "iphone-15"];
  const goodList = listOf({ "home.spec.ts": ["가", "나"] }, projects);
  const summaryOf = (baseList: PlaywrightJsonReport, headList: PlaywrightJsonReport) => {
    const head = inventoryOf(headList);
    const base = inventoryOf(baseList);
    return summaryMarkdownOf({
      run: { ok: true, value: runStatsOf(runReport) },
      head,
      comparison: comparisonFrom(base, head),
      filter: "",
    });
  };

  it("base 목록이 깨지면 비교 못 함과 첫 오류 줄을 쓰고 모든 test()를 뺀 것으로 세지 않는다", () => {
    const markdown = summaryOf(brokenList(), goodList);

    expect(markdown).toContain(
      "비교 못 함: 목록을 만들다 Playwright 오류 2개가 났다. Error: Cannot find module './nope'",
    );
    expect(markdown).not.toContain("뺀 test()");
    expect(markdown).not.toContain("| test() | 0 |");
  });

  it("head 목록이 깨지면 테스트 수와 비교 절 모두 읽지 못했다고 쓰고 0개로 세지 않는다", () => {
    const markdown = summaryOf(goodList, brokenList());

    expect(markdown).toContain("테스트 목록을 읽지 못했다: 목록을 만들다 Playwright 오류 2개가 났다.");
    expect(markdown).toContain("비교 못 함: 목록을 만들다 Playwright 오류 2개가 났다.");
    expect(markdown).not.toContain("- test(): 0개");
  });

  it("base와 head가 모두 깨지면 비교 표를 쓰지 않는다", () => {
    expect(summaryOf(brokenList(), brokenList())).not.toContain("| spec 파일 |");
  });

  it("목록이 깨져도 실행 결과 절은 그대로 쓴다", () => {
    const markdown = summaryOf(brokenList(), brokenList());

    expect(markdown).toContain("- 테스트 실행 시간: 4.0분");
    expect(markdown).toContain("| 합계 | 3 | 1 | 1 | 1 | 1 | 5 | 4.0 |");
  });
});

describe("재시도해서 통과한 테스트", () => {
  const head = inventory(listOf({ "home.spec.ts": ["가"] }, ["chromium"]));

  it("실행 결과 절에 project별로 재시도해서야 통과한 테스트 이름을 쓴다", () => {
    const markdown = summaryMarkdownOf({
      run: { ok: true, value: runStatsOf(runReport) },
      head: { ok: true, value: head },
      filter: "",
    });

    expect(markdown).toContain("재시도해서 통과한 테스트(flaky)");
    expect(markdown).toContain("- iphone-15\n  - home.spec.ts › 홈을 연다");
    expect(markdown).not.toContain("- chromium\n  - ");
    expect(markdown.indexOf("재시도해서 통과한 테스트(flaky)")).toBeLessThan(markdown.indexOf("#### 테스트 수"));
  });

  it("재시도해서 통과한 테스트가 없으면 아무것도 쓰지 않는다", () => {
    const steady: PlaywrightJsonReport = {
      ...runReport,
      suites: [
        {
          title: "a.spec.ts",
          file: "a.spec.ts",
          specs: [
            {
              title: "가",
              file: "a.spec.ts",
              tests: [
                { projectName: "chromium", status: "expected", results: [attempt("2026-11-01T00:00:00.000Z", 1_000)] },
              ],
            },
          ],
        },
      ],
    };
    const markdown = summaryMarkdownOf({
      run: { ok: true, value: runStatsOf(steady) },
      head: { ok: true, value: head },
      filter: "",
    });

    expect(markdown).not.toContain("재시도해서 통과한 테스트");
  });
});
