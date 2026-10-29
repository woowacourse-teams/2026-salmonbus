import { describe, expect, it } from "@jest/globals";
import type { WhatsNewNote } from "./whatsNewNotes";
import { closedRecordFor, firstRecordFor, notesToShowFor, shownRecordFor, type WhatsNewRecord } from "./whatsNewPolicy";

function createNote(id: number, publishedAt: string): WhatsNewNote {
  return { id, publishedAt, features: [`기능 ${id}`] };
}

const note1 = createNote(1, "2026-11-02");
const note2 = createNote(2, "2026-11-12");
const note3 = createNote(3, "2026-11-13");
const NOV_14_MORNING = Date.parse("2026-11-14T08:00:00+09:00");

function recordAfter(lastSeenId: number): WhatsNewRecord {
  return { lastSeenId, autoShow: true, lastShownDay: null };
}

const idsOf = (notes: readonly WhatsNewNote[]) => notes.map((note) => note.id);

describe("firstRecordFor", () => {
  it("처음 온 기기는 지금까지의 소식을 본 것으로 쳐서 아무것도 띄우지 않는다", () => {
    const record = firstRecordFor([note1, note2, note3], false);

    expect(record.lastSeenId).toBe(3);
    expect(notesToShowFor([note1, note2, note3], record, NOV_14_MORNING)).toEqual([]);
  });

  it("전부터 쓰던 기기는 기록이 없어도 아직 띄울 기간인 소식을 보여 준다", () => {
    const record = firstRecordFor([note1, note2, note3], true);

    expect(idsOf(notesToShowFor([note1, note2, note3], record, NOV_14_MORNING))).toEqual([3, 2]);
  });

  it("소식이 하나도 없으면 0번까지 본 것으로 적는다", () => {
    expect(firstRecordFor([], false).lastSeenId).toBe(0);
  });
});

describe("notesToShowFor", () => {
  it("마지막으로 본 번호보다 큰 소식만 최신순으로 모은다", () => {
    expect(idsOf(notesToShowFor([note2, note3], recordAfter(1), NOV_14_MORNING))).toEqual([3, 2]);
    expect(idsOf(notesToShowFor([note2, note3], recordAfter(2), NOV_14_MORNING))).toEqual([3]);
  });

  it("배포한 날부터 7일 동안만 띄운다", () => {
    const lastMinute = Date.parse("2026-11-18T23:59:00+09:00");
    const nextDay = Date.parse("2026-11-19T00:00:00+09:00");

    expect(idsOf(notesToShowFor([note2], recordAfter(0), lastMinute))).toEqual([2]);
    expect(notesToShowFor([note2], recordAfter(0), nextDay)).toEqual([]);
  });

  it("다시 보지 않기를 고른 기기에는 띄우지 않는다", () => {
    const record = { ...recordAfter(0), autoShow: false };

    expect(notesToShowFor([note3], record, NOV_14_MORNING)).toEqual([]);
  });

  describe("하루에 한 번만 띄운다", () => {
    it("오늘 이미 띄웠으면 닫지 않았어도 다시 띄우지 않는다", () => {
      const shown = shownRecordFor(recordAfter(0), NOV_14_MORNING);

      expect(notesToShowFor([note3], shown, NOV_14_MORNING + 60 * 60_000)).toEqual([]);
    });

    it("닫지 않고 앱을 껐으면 한국 날짜로 다음 날 다시 띄운다", () => {
      const lateNight = Date.parse("2026-11-14T23:30:00+09:00");
      const afterMidnight = Date.parse("2026-11-15T00:10:00+09:00");
      const shown = shownRecordFor(recordAfter(0), lateNight);

      expect(idsOf(notesToShowFor([note3], shown, afterMidnight))).toEqual([3]);
    });
  });
});

describe("closedRecordFor", () => {
  it("닫으면 보여 준 소식까지 본 것으로 적어 같은 소식은 다시 뜨지 않는다", () => {
    const shown = shownRecordFor(recordAfter(1), NOV_14_MORNING);
    const closed = closedRecordFor(shown, [note3, note2], "keep");
    const nextDay = NOV_14_MORNING + 24 * 60 * 60_000;

    expect(closed).toEqual({ lastSeenId: 3, autoShow: true, lastShownDay: "2026-11-14" });
    expect(notesToShowFor([note2, note3], closed, nextDay)).toEqual([]);
  });

  it("닫은 뒤 새 배포가 나오면 그 소식은 다시 띄운다", () => {
    const closed = closedRecordFor(recordAfter(1), [note2], "keep");
    const note4 = createNote(4, "2026-11-20");

    expect(idsOf(notesToShowFor([note2, note4], closed, Date.parse("2026-11-20T08:00:00+09:00")))).toEqual([4]);
  });

  it("다시 보지 않기는 앞으로 나올 소식도 자동으로 띄우지 않는다", () => {
    const closed = closedRecordFor(recordAfter(1), [note2], "stop");
    const note4 = createNote(4, "2026-11-20");

    expect(closed.autoShow).toBe(false);
    expect(notesToShowFor([note2, note4], closed, Date.parse("2026-11-20T08:00:00+09:00"))).toEqual([]);
  });

  it("마지막으로 본 번호를 뒤로 돌리지 않는다", () => {
    expect(closedRecordFor(recordAfter(3), [note2], "keep").lastSeenId).toBe(3);
  });
});
