import { describe, expect, it } from "@jest/globals";
import { noteHeadingLabelOf } from "./whatsNewCopy";

describe("noteHeadingLabelOf", () => {
  it("버전과 배포 날짜를 앞자리 0 없이 보여 준다", () => {
    const note = { id: 1, version: "1.3.0", publishedAt: "2026-11-02", features: ["기능"] };

    expect(noteHeadingLabelOf(note)).toBe("v1.3.0 / 11월 2일");
  });
});
