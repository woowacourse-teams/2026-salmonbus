import { describe, expect, it } from "@jest/globals";
import { editionsFrom, editionViewFor, type EditionView } from "./editionPolicy";
import { editionPeriodLabelOf } from "./privacyCopy";

const editions = editionsFrom([
  { fileName: "2026-11-04.md", markdown: "# 개인정보 처리방침\n" },
  { fileName: "2027-01-01.md", markdown: "# 개인정보 처리방침\n" },
]);

function periodLabelsOf(view: EditionView): string[] {
  if (view.kind !== "edition") throw new Error("판을 찾지 못했습니다.");
  return [view.summary, ...view.others].map(editionPeriodLabelOf);
}

describe("editionPeriodLabelOf", () => {
  it("시행 전인 판은 적용 예정, 다음 판이 있는 판은 끝나는 날까지, 가장 새 판은 시작하는 날만 앞자리 0 없이 쓴다", () => {
    expect(periodLabelsOf(editionViewFor(editions, undefined, Date.parse("2026-12-01T09:00:00+09:00")))).toEqual([
      "2026년 11월 4일부터 2026년 12월 31일까지 적용",
      "2027년 1월 1일부터 적용 예정",
    ]);
    expect(periodLabelsOf(editionViewFor(editions, undefined, Date.parse("2027-01-05T09:00:00+09:00")))).toEqual([
      "2027년 1월 1일부터 적용",
      "2026년 11월 4일부터 2026년 12월 31일까지 적용",
    ]);
  });
});
