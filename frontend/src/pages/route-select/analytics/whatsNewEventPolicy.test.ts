import { describe, expect, it } from "@jest/globals";
import type { WhatsNewNote } from "@/shared/whats-new/whatsNewNotes";
import { whatsNewClosedPropertiesOf, whatsNewViewedPropertiesOf } from "./whatsNewEventPolicy";

const olderNote: WhatsNewNote = { id: 2, publishedAt: "2026-11-02", features: ["기능 가", "기능 나"] };
const newerNote: WhatsNewNote = { id: 3, publishedAt: "2026-11-12", features: ["기능 다", "기능 라", "기능 마"] };

describe("whatsNewViewedPropertiesOf", () => {
  it("함께 띄운 소식의 순서와 상관없이 가장 큰 소식 번호와 모든 소식의 기능 줄 수를 보낸다", () => {
    const expected = { latest_note_id: 3, feature_count: 5, new_route_count: 0 };

    expect(whatsNewViewedPropertiesOf([newerNote, olderNote], 0)).toEqual(expected);
    expect(whatsNewViewedPropertiesOf([olderNote, newerNote], 0)).toEqual(expected);
  });

  it("새 노선만 띄우면 소식 번호와 기능 줄 수는 0이고 새 노선 수를 보낸다", () => {
    expect(whatsNewViewedPropertiesOf([], 7)).toEqual({ latest_note_id: 0, feature_count: 0, new_route_count: 7 });
  });
});

describe("whatsNewClosedPropertiesOf", () => {
  it("가장 큰 소식 번호와 닫은 방법을 그대로 보내고 열어 둔 시간은 ms 정수로 반올림한다", () => {
    expect(whatsNewClosedPropertiesOf([olderNote, newerNote], "escape", 1234.6)).toEqual({
      latest_note_id: 3,
      close_method: "escape",
      open_duration_ms: 1235,
    });
  });
});
