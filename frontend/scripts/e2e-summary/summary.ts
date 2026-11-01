// Playwright JSON 리포터 결과(실행 결과와 --list 목록)에서 E2E 실행 시간과 개수를 세어 GitHub 단계 요약(markdown)을 만든다.

type TestStatus = "expected" | "unexpected" | "flaky" | "skipped";

interface JsonResult {
  startTime: string;
  duration: number;
  status: string;
}

interface JsonTest {
  projectName: string;
  status: TestStatus;
  results: JsonResult[];
}

interface JsonSpec {
  title: string;
  file: string;
  tests: JsonTest[];
}

interface JsonSuite {
  title: string;
  file: string;
  specs?: JsonSpec[];
  suites?: JsonSuite[];
}

export interface PlaywrightJsonReport {
  stats: { duration: number };
  suites: JsonSuite[];
  errors: unknown[];
}

interface TestCase {
  // 파일과 describe, test 제목을 이은 이름. 같은 파일 안의 같은 제목은 Playwright가 막으므로 test() 하나를 가리킨다.
  name: string;
  file: string;
  projectName: string;
  status: TestStatus;
  results: JsonResult[];
}

export interface ProjectRun {
  project: string;
  executed: number;
  passed: number;
  flaky: number;
  failed: number;
  skipped: number;
  attempts: number;
  testMs: number;
}

export interface RunStats {
  playwrightMs: number;
  testSpanMs: number | null;
  projects: ProjectRun[];
  errorCount: number;
}

export interface Inventory {
  fileCount: number;
  testNames: Set<string>;
  // test 수 x project 수. --list에 넘긴 project 기준이다.
  runCount: number;
  projects: string[];
}

export interface Comparison {
  base: Inventory;
  head: Inventory;
  added: string[];
  removed: string[];
}

export type Loaded<T> = { ok: true; value: T } | { ok: false; reason: string };

function testCasesOf(report: PlaywrightJsonReport): TestCase[] {
  const cases: TestCase[] = [];
  const visit = (suite: JsonSuite, describeTitles: string[]) => {
    for (const spec of suite.specs ?? []) {
      const name = [spec.file, ...describeTitles, spec.title].join(" › ");
      for (const test of spec.tests) {
        cases.push({
          name,
          file: spec.file,
          projectName: test.projectName,
          status: test.status,
          results: test.results,
        });
      }
    }
    for (const child of suite.suites ?? []) visit(child, [...describeTitles, child.title]);
  };
  // 맨 위 suite는 파일이고 제목이 파일 이름이라 describe 제목에 넣지 않는다.
  for (const fileSuite of report.suites) visit(fileSuite, []);
  return cases;
}

function uniqueSorted(values: Iterable<string>): string[] {
  return [...new Set(values)].sort();
}

export function runStatsOf(report: PlaywrightJsonReport): RunStats {
  const cases = testCasesOf(report);
  const attempts = cases.flatMap((testCase) => testCase.results.filter((result) => result.status !== "skipped"));
  const starts = attempts.map((result) => Date.parse(result.startTime));
  const ends = attempts.map((result) => Date.parse(result.startTime) + result.duration);

  const projects = uniqueSorted(cases.map((testCase) => testCase.projectName)).map((project) => {
    const projectCases = cases.filter((testCase) => testCase.projectName === project);
    const countOf = (status: TestStatus) => projectCases.filter((testCase) => testCase.status === status).length;
    const projectAttempts = projectCases.flatMap((testCase) =>
      testCase.results.filter((result) => result.status !== "skipped"),
    );
    return {
      project,
      executed: projectCases.length - countOf("skipped"),
      passed: countOf("expected"),
      flaky: countOf("flaky"),
      failed: countOf("unexpected"),
      skipped: countOf("skipped"),
      attempts: projectAttempts.length,
      testMs: projectAttempts.reduce((total, result) => total + result.duration, 0),
    };
  });

  return {
    playwrightMs: report.stats.duration,
    testSpanMs: attempts.length > 0 ? Math.max(...ends) - Math.min(...starts) : null,
    projects,
    errorCount: report.errors.length,
  };
}

export function inventoryOf(list: PlaywrightJsonReport): Inventory {
  const cases = testCasesOf(list);
  return {
    fileCount: new Set(cases.map((testCase) => testCase.file)).size,
    testNames: new Set(cases.map((testCase) => testCase.name)),
    runCount: cases.length,
    projects: uniqueSorted(cases.map((testCase) => testCase.projectName)),
  };
}

export function comparisonOf(base: Inventory, head: Inventory): Comparison {
  return {
    base,
    head,
    added: [...head.testNames].filter((name) => !base.testNames.has(name)).sort(),
    removed: [...base.testNames].filter((name) => !head.testNames.has(name)).sort(),
  };
}

function minutesOf(milliseconds: number): string {
  return (milliseconds / 60_000).toFixed(1);
}

function signed(value: number): string {
  return value > 0 ? `+${value}` : String(value);
}

function namesBlock(summary: string, names: string[]): string[] {
  if (names.length === 0) return [];
  return [
    "",
    `<details><summary>${summary} ${names.length}개</summary>`,
    "",
    ...names.map((name) => `- ${name}`),
    "",
    "</details>",
  ];
}

function runSection(run: Loaded<RunStats>, filter: string): string[] {
  if (!run.ok) return [`실행 결과를 읽지 못했다: ${run.reason}`];
  const { value } = run;
  const lines = [
    `- 테스트 실행 시간: ${value.testSpanMs === null ? "실행한 테스트 없음" : `${minutesOf(value.testSpanMs)}분`}`,
    `- Playwright 전체 시간: ${minutesOf(value.playwrightMs)}분`,
    "",
    "테스트 실행 시간은 첫 테스트 시작부터 마지막 테스트 끝까지다. Playwright 전체 시간은 웹 서버를 띄우는 시간(pnpm build 포함)까지 더한 값이다. 둘 다 의존성 설치와 컨테이너 준비 시간은 넣지 않는다.",
  ];
  if (filter) lines.push("", `이번 실행 필터: \`${filter}\``);
  if (value.errorCount > 0) {
    lines.push(
      "",
      `Playwright 오류 ${value.errorCount}개가 있다(웹 서버 기동 실패 등). 실행 횟수가 적게 보일 수 있다.`,
    );
  }

  const total = (pick: (project: ProjectRun) => number) =>
    value.projects.reduce((sum, project) => sum + pick(project), 0);
  lines.push(
    "",
    "| project | 실행 | 통과 | flaky | 실패 | 건너뜀 | 시도(재시도 포함) | 테스트 시간 합(분) |",
    "|---|---:|---:|---:|---:|---:|---:|---:|",
    ...value.projects.map(
      (project) =>
        `| ${project.project} | ${project.executed} | ${project.passed} | ${project.flaky} | ${project.failed} ` +
        `| ${project.skipped} | ${project.attempts} | ${minutesOf(project.testMs)} |`,
    ),
    `| 합계 | ${total((project) => project.executed)} | ${total((project) => project.passed)} ` +
      `| ${total((project) => project.flaky)} | ${total((project) => project.failed)} ` +
      `| ${total((project) => project.skipped)} | ${total((project) => project.attempts)} ` +
      `| ${minutesOf(total((project) => project.testMs))} |`,
    "",
    "테스트 시간 합은 테스트마다 걸린 시간을 더한 값이라 worker가 여럿이면 실행 시간보다 길다.",
  );
  return lines;
}

function inventorySection(head: Loaded<Inventory>): string[] {
  if (!head.ok) return [`테스트 목록을 읽지 못했다: ${head.reason}`];
  return [
    `- spec 파일: ${head.value.fileCount}개`,
    `- test(): ${head.value.testNames.size}개`,
    "",
    "브라우저 하나 기준이고, 반복문으로 만든 테스트는 펼친 개수다. 실행 필터와 상관없이 전체 목록에서 센다.",
  ];
}

function comparisonSection(comparison: Loaded<Comparison>): string[] {
  if (!comparison.ok) return [`비교 못 함: ${comparison.reason}`];
  const { base, head, added, removed } = comparison.value;
  return [
    `| | base | 이 PR | 차이 |`,
    "|---|---:|---:|---:|",
    `| spec 파일 | ${base.fileCount} | ${head.fileCount} | ${signed(head.fileCount - base.fileCount)} |`,
    `| test() | ${base.testNames.size} | ${head.testNames.size} | ${signed(head.testNames.size - base.testNames.size)} |`,
    `| 실행 횟수(${head.projects.join(", ")}, 필터 적용 전 목록 기준) | ${base.runCount} | ${head.runCount} | ${signed(head.runCount - base.runCount)} |`,
    "",
    `더한 test() ${added.length}개, 뺀 test() ${removed.length}개. 제목이나 파일이 바뀐 테스트는 뺀 것과 더한 것에 함께 들어간다.`,
    ...namesBlock("더한 test()", added),
    ...namesBlock("뺀 test()", removed),
  ];
}

export interface SummaryInput {
  run: Loaded<RunStats>;
  head: Loaded<Inventory>;
  // dev push처럼 비교하지 않는 실행은 undefined다.
  comparison?: Loaded<Comparison>;
  filter: string;
}

export function summaryMarkdownOf({ run, head, comparison, filter }: SummaryInput): string {
  const lines = [
    "### E2E 실행 시간과 개수",
    "",
    ...runSection(run, filter),
    "",
    "#### 테스트 수",
    "",
    ...inventorySection(head),
  ];
  if (comparison) lines.push("", "#### 이 PR이 더한 E2E(base: HEAD^1)", "", ...comparisonSection(comparison));
  return `${lines.join("\n")}\n`;
}
