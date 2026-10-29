import { describe, expect, it } from "@jest/globals";
import { WHATS_NEW_NOTES } from "./whatsNewNotes";

const KST_DAY = /^\d{4}-\d{2}-\d{2}$/;

describe("WHATS_NEW_NOTES", () => {
  it("번호는 양의 정수이고 겹치지 않으며 위로 갈수록 크다", () => {
    const ids = WHATS_NEW_NOTES.map((note) => note.id);

    expect(ids.every((id) => Number.isSafeInteger(id) && id > 0)).toBe(true);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toEqual([...ids].sort((left, right) => right - left));
  });

  it("배포 날짜는 실제로 있는 한국 날짜이고 위로 갈수록 늦다", () => {
    const days = WHATS_NEW_NOTES.map((note) => note.publishedAt);

    days.forEach((day) => {
      expect(day).toMatch(KST_DAY);
      expect(new Date(`${day}T00:00:00Z`).toISOString().slice(0, 10)).toBe(day);
    });
    expect(days).toEqual([...days].sort().reverse());
  });

  it("항목마다 보여 줄 기능 문장이 하나 이상 있다", () => {
    WHATS_NEW_NOTES.forEach((note) => {
      expect(note.features.length).toBeGreaterThan(0);
      note.features.forEach((feature) => expect(feature.trim()).not.toBe(""));
    });
  });
});
