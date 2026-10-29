import { describe, expect, it } from "@jest/globals";
import { noteHeadingLabelOf } from "./whatsNewCopy";

describe("noteHeadingLabelOf", () => {
  it("배포 날짜를 앞자리 0 없이 보여 준다", () => {
    const note = { id: 1, publishedAt: "2026-11-02", features: ["기능"] };

    expect(noteHeadingLabelOf(note)).toBe("11월 2일 업데이트");
  });
});
