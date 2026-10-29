import { describe, expect, it } from "@jest/globals";
import { editionsFrom, editionViewFor, type EditionFile } from "./editionPolicy";

function createEditionFile(date: string, title = "개인정보 처리방침"): EditionFile {
  return { fileName: `${date}.md`, markdown: `# ${title}\n\n본문\n` };
}

const firstEdition = createEditionFile("2026-11-04");
const secondEdition = createEditionFile("2027-01-01", "개인정보 처리방침 2판");
const thirdEdition = createEditionFile("2027-03-01", "개인정보 처리방침 3판");
const DEC_1 = Date.parse("2026-12-01T09:00:00+09:00");

describe("editionsFrom", () => {
  it("파일 이름의 날짜를 시행일로 읽고 새 판부터 늘어놓는다", () => {
    const editions = editionsFrom([firstEdition, thirdEdition, secondEdition]);

    expect(editions.map((edition) => edition.effectiveDate)).toEqual(["2027-03-01", "2027-01-01", "2026-11-04"]);
    expect(editions[0].document.title).toBe("개인정보 처리방침 3판");
  });

  it.each(["README.md", "2026-02-30.md", "2026-11-04.txt"])(
    "시행일이 아닌 파일 이름(%s)은 그 파일 이름으로 알린다",
    (fileName) => {
      expect(() => editionsFrom([{ ...firstEdition, fileName }])).toThrow(fileName);
    },
  );

  it("원고를 읽지 못하면 파일 이름과 줄 번호로 알린다", () => {
    expect(() => editionsFrom([{ fileName: "2027-01-01.md", markdown: "# 처리방침\n\n본문 *기울임*" }])).toThrow(
      "2027-01-01.md 3번째 줄",
    );
  });

  it("원고가 하나도 없으면 알린다", () => {
    expect(() => editionsFrom([])).toThrow();
  });
});

describe("editionViewFor", () => {
  const editions = editionsFrom([firstEdition, secondEdition]);

  it("주소에 날짜가 없으면 시행 중인 판 가운데 가장 새 판을 보여 주고, 시행 전인 새 판은 다른 판에 넣는다", () => {
    const view = editionViewFor(editions, undefined, DEC_1);

    expect(view).toMatchObject({
      kind: "edition",
      title: "개인정보 처리방침",
      summary: { effectiveDate: "2026-11-04", lastDay: "2026-12-31", isCurrent: true, isUpcoming: false },
      others: [{ effectiveDate: "2027-01-01", lastDay: null, isCurrent: false, isUpcoming: true }],
    });
  });

  it("새 판은 한국 시간으로 시행일 0시부터 지금 판이 된다", () => {
    const lastMinute = Date.parse("2026-12-31T23:59:00+09:00");
    const midnight = Date.parse("2027-01-01T00:00:00+09:00");

    expect(editionViewFor(editions, undefined, lastMinute)).toMatchObject({ title: "개인정보 처리방침" });
    expect(editionViewFor(editions, undefined, midnight)).toMatchObject({
      title: "개인정보 처리방침 2판",
      summary: { effectiveDate: "2027-01-01", isCurrent: true, isUpcoming: false },
      others: [{ effectiveDate: "2026-11-04", lastDay: "2026-12-31", isCurrent: false }],
    });
  });

  it("첫 판의 시행일 전에는 첫 판을 시행 예정인 지금 판으로 보여 준다", () => {
    const onlyEdition = editionsFrom([firstEdition]);
    const dayBefore = Date.parse("2026-11-03T23:59:00+09:00");
    const effectiveDay = Date.parse("2026-11-04T00:00:00+09:00");

    expect(editionViewFor(onlyEdition, undefined, dayBefore)).toMatchObject({
      kind: "edition",
      summary: { effectiveDate: "2026-11-04", lastDay: null, isCurrent: true, isUpcoming: true },
      others: [],
    });
    expect(editionViewFor(onlyEdition, undefined, effectiveDay)).toMatchObject({
      summary: { isCurrent: true, isUpcoming: false },
    });
  });

  it("주소의 날짜가 어떤 판의 시행일이면 그 판을 다음 판 전날까지의 기간과 함께 보여 준다", () => {
    const midnight = Date.parse("2027-01-01T00:00:00+09:00");

    expect(editionViewFor(editions, "2026-11-04", midnight)).toMatchObject({
      kind: "edition",
      title: "개인정보 처리방침",
      summary: { effectiveDate: "2026-11-04", lastDay: "2026-12-31", isCurrent: false, isUpcoming: false },
      others: [{ effectiveDate: "2027-01-01", isCurrent: true }],
    });
  });

  it.each(["2025-01-01", "2026-13-01", "latest"])(
    "주소의 날짜(%s)에 맞는 판이 없으면 지금 판의 제목과 함께 알린다",
    (date) => {
      expect(editionViewFor(editions, date, DEC_1)).toEqual({ kind: "unknownDate", title: "개인정보 처리방침" });
    },
  );
});
