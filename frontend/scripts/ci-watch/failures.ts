import { execFileSync } from "node:child_process";
import { readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

export interface FailedTest {
  file: string;
  line: number;
  project: string;
  titlePath: string[];
  // playwright-report 폴더 기준 경로. 첫 실패 시도의 기록만 trace까지 남는다.
  errorContext: string | null;
}

interface ReportTest {
  title: string;
  path: string[];
  projectName: string;
  location: { file: string; line: number };
  outcome: "expected" | "unexpected" | "flaky" | "skipped";
  results: { attachments: { name: string; path?: string }[] }[];
}

export interface PlaywrightReport {
  stats: { unexpected: number };
  files: { tests: ReportTest[] }[];
}

// HTML 리포트는 테스트 결과를 index.html 안에 base64 zip으로 넣어 둔다.
const embeddedZip = /data:application\/zip;base64,([A-Za-z0-9+/=]+)/;

export function loadReport(reportDir: string): PlaywrightReport {
  const html = readFileSync(path.join(reportDir, "index.html"), "utf8");
  const encoded = embeddedZip.exec(html)?.[1];
  if (!encoded) throw new Error(`${reportDir}/index.html에서 테스트 결과를 찾지 못했습니다.`);

  const zipPath = path.join(reportDir, "report.zip");
  writeFileSync(zipPath, Buffer.from(encoded, "base64"));
  try {
    const json = execFileSync("unzip", ["-p", zipPath, "report.json"], {
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    });
    return JSON.parse(json);
  } finally {
    rmSync(zipPath, { force: true });
  }
}

// 재시도로 통과한 테스트(flaky)는 실패로 세지 않는다.
export function failedTestsFrom(report: PlaywrightReport): FailedTest[] {
  const failures = report.files.flatMap((file) =>
    file.tests
      .filter((test) => test.outcome === "unexpected")
      .map((test) => ({
        file: test.location.file,
        line: test.location.line,
        project: test.projectName,
        titlePath: [...test.path, test.title],
        errorContext:
          test.results[0]?.attachments.find((attachment) => attachment.name === "error-context")?.path ?? null,
      })),
  );
  // 리포트 모양이 바뀌어 실패를 못 읽으면 "E2E 실패 없음"으로 잘못 알리게 된다.
  if (failures.length !== report.stats.unexpected) {
    throw new Error(
      `리포트의 실패 수(${report.stats.unexpected})와 읽은 실패 수(${failures.length})가 다릅니다. Playwright 리포트 모양이 바뀌었는지 확인해 주세요.`,
    );
  }
  return failures;
}

// PR은 Chromium과 iPhone 15만, dev push는 네 프로젝트를 돌리고 줄 번호도 브랜치마다 달라서 둘 다 비교하지 않는다.
function testKeyOf(test: FailedTest) {
  return [test.file, ...test.titlePath].join(" › ");
}

export function failureSplitFrom(failures: FailedTest[], devFailures: FailedTest[]) {
  const devKeys = new Set(devFailures.map(testKeyOf));
  return {
    sharedWithDev: failures.filter((test) => devKeys.has(testKeyOf(test))),
    branchOnly: failures.filter((test) => !devKeys.has(testKeyOf(test))),
  };
}

export function testLabelOf(test: FailedTest) {
  return `[${test.project}] e2e/${test.file}:${test.line} › ${test.titlePath.join(" › ")}`;
}
