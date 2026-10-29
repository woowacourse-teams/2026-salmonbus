import { describe, expect, it } from "@jest/globals";
import type { RouteStatus, RouteSummary } from "@/shared/api/routeForecast.types";
import { firstSeenTimesFor, type FirstSeenTimes } from "@/shared/route-list/newRoutePolicy";
import type { WhatsNewNote } from "./whatsNewNotes";
import {
  closedRecordFor,
  firstRecordFor,
  newRoutesToShowFor,
  notesToShowFor,
  routesSeenRecordFor,
  shownRecordFor,
  type WhatsNewRecord,
} from "./whatsNewPolicy";

function createNote(id: number, publishedAt: string): WhatsNewNote {
  return { id, publishedAt, features: [`기능 ${id}`] };
}

const note1 = createNote(1, "2026-11-02");
const note2 = createNote(2, "2026-11-12");
const note3 = createNote(3, "2026-11-13");
const NOV_14_MORNING = Date.parse("2026-11-14T08:00:00+09:00");
const DAY = 24 * 60 * 60_000;
const HOUR = 60 * 60_000;

function recordAfter(lastSeenId: number): WhatsNewRecord {
  return { lastSeenId, lastShownDay: null, routesSeenUntil: 0 };
}

function routesSeenAfter(routesSeenUntil: number): WhatsNewRecord {
  return { ...recordAfter(0), routesSeenUntil };
}

function createRoute(displayName: string, status: RouteStatus = "FORECAST_READY"): RouteSummary {
  return { id: displayName, displayName, startStopName: "기점", endStopName: "종점", status };
}

const idsOf = (notes: readonly WhatsNewNote[]) => notes.map((note) => note.id);
const namesOf = (routes: readonly RouteSummary[]) => routes.map((route) => route.displayName);

describe("firstRecordFor", () => {
  it("처음 온 기기는 지금까지의 소식을 본 것으로 쳐서 아무것도 띄우지 않는다", () => {
    const record = firstRecordFor([note1, note2, note3], false, NOV_14_MORNING);

    expect(record.lastSeenId).toBe(3);
    expect(notesToShowFor([note1, note2, note3], record, NOV_14_MORNING)).toEqual([]);
  });

  it("전부터 쓰던 기기는 기록이 없어도 아직 띄울 기간인 소식을 보여 준다", () => {
    const record = firstRecordFor([note1, note2, note3], true, NOV_14_MORNING);

    expect(idsOf(notesToShowFor([note1, note2, note3], record, NOV_14_MORNING))).toEqual([3, 2]);
  });

  it("소식이 하나도 없으면 0번까지 본 것으로 적는다", () => {
    expect(firstRecordFor([], false, NOV_14_MORNING).lastSeenId).toBe(0);
  });

  it.each([
    { device: "처음 온 기기", hasVisitedBefore: false },
    { device: "전부터 쓰던 기기", hasVisitedBefore: true },
  ])("$device도 기록을 만든 날 한국 날짜가 끝날 때까지 처음 본 노선을 본 것으로 적는다", ({ hasVisitedBefore }) => {
    expect(firstRecordFor([], hasVisitedBefore, NOV_14_MORNING).routesSeenUntil).toBe(
      Date.parse("2026-11-14T23:59:59.999+09:00"),
    );
  });

  it("전부터 쓰던 기기는 기록을 만들기 전부터 NEW이던 노선을 띄우지 않고, 다음 날부터 그 뒤에 처음 본 노선만 띄운다", () => {
    const record = firstRecordFor([], true, NOV_14_MORNING);
    const routes = [createRoute("9300"), createRoute("5600")];
    const times: FirstSeenTimes = new Map([
      ["9300", NOV_14_MORNING - 3 * DAY],
      ["5600", NOV_14_MORNING + DAY - HOUR],
    ]);

    expect(newRoutesToShowFor(routes, times, record, NOV_14_MORNING)).toEqual([]);
    expect(namesOf(newRoutesToShowFor(routes, times, record, NOV_14_MORNING + DAY))).toEqual(["5600"]);
  });

  it("처음 온 기기가 첫 방문에 받은 노선은 다음 날 띄우지 않고, 그 뒤에 처음 본 노선만 띄운다", () => {
    const record = firstRecordFor([], false, NOV_14_MORNING);
    const firstVisit = firstSeenTimesFor([createRoute("9300")], new Map(), NOV_14_MORNING + 1_000);
    const nextDay = NOV_14_MORNING + DAY;
    const routes = [createRoute("9300"), createRoute("5600")];

    const times = firstSeenTimesFor(routes, firstVisit, nextDay);

    expect(namesOf(newRoutesToShowFor(routes, times, record, nextDay))).toEqual(["5600"]);
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
    const closed = closedRecordFor(shown, [note3, note2]);
    const nextDay = NOV_14_MORNING + 24 * 60 * 60_000;

    expect(closed).toEqual({ lastSeenId: 3, lastShownDay: "2026-11-14", routesSeenUntil: 0 });
    expect(notesToShowFor([note2, note3], closed, nextDay)).toEqual([]);
  });

  it("닫은 뒤 새 배포가 나오면 그 소식은 다시 띄운다", () => {
    const closed = closedRecordFor(recordAfter(1), [note2]);
    const note4 = createNote(4, "2026-11-20");

    expect(idsOf(notesToShowFor([note2, note4], closed, Date.parse("2026-11-20T08:00:00+09:00")))).toEqual([4]);
  });

  it("마지막으로 본 번호를 뒤로 돌리지 않는다", () => {
    expect(closedRecordFor(recordAfter(3), [note2]).lastSeenId).toBe(3);
  });
});

describe("newRoutesToShowFor", () => {
  const baseline = NOV_14_MORNING - DAY;

  it("기준 시각 뒤에 처음 본 서비스 중 노선만 보여 주고, 원래 알던 노선과 기준 시각 전에 본 노선은 뺀다", () => {
    const routes = [createRoute("1650"), createRoute("3330"), createRoute("5600"), createRoute("9300")];
    const times: FirstSeenTimes = new Map([
      ["1650", 0],
      ["3330", baseline - HOUR],
      ["5600", baseline + HOUR],
      ["9300", NOV_14_MORNING],
    ]);

    expect(namesOf(newRoutesToShowFor(routes, times, routesSeenAfter(baseline), NOV_14_MORNING))).toEqual([
      "5600",
      "9300",
    ]);
  });

  it("처음 본 지 7일이 지나 NEW가 아닌 노선은 보여 주지 않는다", () => {
    const routes = [createRoute("3000"), createRoute("3100")];
    const times: FirstSeenTimes = new Map([
      ["3000", NOV_14_MORNING - 7 * DAY],
      ["3100", NOV_14_MORNING - 7 * DAY + 1],
    ]);

    expect(namesOf(newRoutesToShowFor(routes, times, routesSeenAfter(0), NOV_14_MORNING))).toEqual(["3100"]);
  });

  it("준비 중 노선은 처음 본 기록이 있어도 보여 주지 않는다", () => {
    const routes = [createRoute("3000", "PREPARING"), createRoute("3100")];
    const times: FirstSeenTimes = new Map([
      ["3000", NOV_14_MORNING - HOUR],
      ["3100", NOV_14_MORNING - HOUR],
    ]);

    expect(namesOf(newRoutesToShowFor(routes, times, routesSeenAfter(0), NOV_14_MORNING))).toEqual(["3100"]);
  });

  it("응답 순서가 아니라 노선 번호 순서로 놓는다", () => {
    const routes = [createRoute("G8110"), createRoute("5600"), createRoute("1500-2"), createRoute("3000")];
    const times: FirstSeenTimes = new Map(routes.map((route) => [route.id, NOV_14_MORNING - HOUR]));

    expect(namesOf(newRoutesToShowFor(routes, times, routesSeenAfter(0), NOV_14_MORNING))).toEqual([
      "1500-2",
      "3000",
      "5600",
      "G8110",
    ]);
  });

  it("소식처럼 오늘 이미 띄웠으면 보여 주지 않고, 다음 날 보여 준다", () => {
    const routes = [createRoute("5600")];
    const times: FirstSeenTimes = new Map([["5600", NOV_14_MORNING - HOUR]]);
    const shown = shownRecordFor(routesSeenAfter(0), NOV_14_MORNING - HOUR);

    expect(newRoutesToShowFor(routes, times, shown, NOV_14_MORNING)).toEqual([]);
    expect(namesOf(newRoutesToShowFor(routes, times, shown, NOV_14_MORNING + DAY))).toEqual(["5600"]);
  });
});

describe("routesSeenRecordFor", () => {
  it("닫을 때 받은 노선 목록 시각까지 본 것으로 적어, 그때 보여 준 노선은 다시 띄우지 않고 그 뒤에 본 노선만 띄운다", () => {
    const shownAt = NOV_14_MORNING;
    const routes = [createRoute("5600"), createRoute("9300")];
    const times: FirstSeenTimes = new Map([
      ["5600", shownAt - HOUR],
      ["9300", shownAt + DAY - HOUR],
    ]);

    const seen = routesSeenRecordFor(routesSeenAfter(0), shownAt);

    expect(namesOf(newRoutesToShowFor(routes, times, seen, shownAt + DAY))).toEqual(["9300"]);
  });

  it("기준 시각을 뒤로 돌리지 않는다", () => {
    expect(routesSeenRecordFor(routesSeenAfter(NOV_14_MORNING), NOV_14_MORNING - DAY).routesSeenUntil).toBe(
      NOV_14_MORNING,
    );
  });
});
