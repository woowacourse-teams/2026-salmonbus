import { describe, expect, it } from "@jest/globals";
import { moreRoutesLabelOf, noteHeadingLabelOf } from "./whatsNewCopy";

describe("noteHeadingLabelOf", () => {
  it("배포 날짜를 앞자리 0 없이 보여 준다", () => {
    const note = { id: 1, publishedAt: "2026-11-02", features: ["기능"] };

    expect(noteHeadingLabelOf(note)).toBe("11월 2일 업데이트");
  });
});

describe("moreRoutesLabelOf", () => {
  it("보여 주지 못한 노선 개수를 알린다", () => {
    expect(moreRoutesLabelOf(2)).toBe("노선 2개가 더 추가됐어요");
  });
});
