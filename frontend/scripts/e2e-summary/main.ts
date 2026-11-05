import { appendFileSync, readFileSync } from "node:fs";
import process from "node:process";
import { parseArgs } from "node:util";
import {
  comparisonFrom,
  inventoryOf,
  runStatsOf,
  summaryMarkdownOf,
  type Loaded,
  type PlaywrightJsonReport,
} from "./summary.ts";

// 요약은 E2E 결과를 보여 줄 뿐이라, 파일이 없거나 깨져도 실패로 끝내지 않고 요약에 이유를 남긴다.
const usage = `사용법
  node scripts/e2e-summary/main.ts --results <실행 결과 JSON> --list <테스트 목록 JSON>
    [--base-list <base 테스트 목록 JSON>] [--filter <실행 필터>] [--output <요약을 덧붙일 파일>]

  목록 JSON은 playwright test --list --reporter=json 으로 만든다.
  --base-list를 넘기면 PR 비교 절을 넣는다. 파일이 없으면 "비교 못 함"으로 남긴다.`;

function loaded<T>(
  path: string | undefined,
  label: string,
  read: (report: PlaywrightJsonReport) => Loaded<T>,
): Loaded<T> {
  if (!path) return { ok: false, reason: `${label} 경로를 받지 못했다.` };
  try {
    return read(JSON.parse(readFileSync(path, "utf8")));
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    return { ok: false, reason: `${label}(${path})를 읽지 못했다. ${message}` };
  }
}

const { values } = parseArgs({
  options: {
    results: { type: "string" },
    list: { type: "string" },
    "base-list": { type: "string" },
    filter: { type: "string", default: "" },
    output: { type: "string" },
    help: { type: "boolean" },
  },
});

if (values.help) {
  console.log(usage);
} else {
  const head = loaded(values.list, "테스트 목록", inventoryOf);
  const baseList = values["base-list"];
  const markdown = summaryMarkdownOf({
    run: loaded(values.results, "실행 결과", (report) => ({ ok: true, value: runStatsOf(report) })),
    head,
    ...(baseList === undefined ? {} : { comparison: comparisonFrom(loaded(baseList, "base 목록", inventoryOf), head) }),
    filter: values.filter.trim(),
  });
  if (values.output) appendFileSync(values.output, markdown);
  else process.stdout.write(markdown);
}
