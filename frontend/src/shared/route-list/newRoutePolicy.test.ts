import { describe, expect, it } from "@jest/globals";
import type { RouteStatus, RouteSummary } from "@/shared/api/routeForecast.types";
import { firstSeenTimesFor, newRouteIdsFor, type FirstSeenTimes } from "./newRoutePolicy";

const AFTER_DEADLINE = Date.parse("2027-01-10T08:00:00+09:00");
const DAY = 24 * 60 * 60_000;
const NO_RECORD: FirstSeenTimes = new Map();

function createRoute(id: string, status: RouteStatus = "FORECAST_READY"): RouteSummary {
  return { id, displayName: id, startStopName: "기점", endStopName: "종점", status };
}

const routeA = createRoute("A");
const routeB = createRoute("B");
const routeC = createRoute("C");

describe("firstSeenTimesFor", () => {
  describe("기록이 없는 기기(첫 방문)는", () => {
    it("지금 서비스 중인 노선에 NEW를 붙이지 않는다", () => {
      const times = firstSeenTimesFor([routeA, routeB], NO_RECORD, AFTER_DEADLINE);

      expect(newRouteIdsFor(times, AFTER_DEADLINE)).toEqual(new Set());
    });

    it("서비스 중 노선이 하나도 없으면 아무것도 적지 않아 다음 방문도 첫 방문이다", () => {
      const allPreparing = firstSeenTimesFor([createRoute("A", "PREPARING")], NO_RECORD, AFTER_DEADLINE);
      const nextVisit = firstSeenTimesFor([routeA, routeB], allPreparing, AFTER_DEADLINE + DAY);

      expect(allPreparing.size).toBe(0);
      expect(newRouteIdsFor(nextVisit, AFTER_DEADLINE + DAY)).toEqual(new Set());
    });
  });

  describe("기록이 있는 기기는", () => {
    it("새로 생긴 서비스 중 노선만 NEW로 적고, 이미 적은 노선의 시각은 그대로 둔다", () => {
      const firstVisit = firstSeenTimesFor([routeA], NO_RECORD, AFTER_DEADLINE);
      const secondVisit = firstSeenTimesFor([routeA, routeB], firstVisit, AFTER_DEADLINE + 2 * DAY);
      const thirdVisit = firstSeenTimesFor([routeA, routeB, routeC], secondVisit, AFTER_DEADLINE + 4 * DAY);

      expect(newRouteIdsFor(thirdVisit, AFTER_DEADLINE + 4 * DAY)).toEqual(new Set(["B", "C"]));
      expect(newRouteIdsFor(thirdVisit, AFTER_DEADLINE + 10 * DAY)).toEqual(new Set(["C"]));
    });

    it("준비 중 노선은 적지 않는다", () => {
      const firstVisit = firstSeenTimesFor([routeA], NO_RECORD, AFTER_DEADLINE);
      const times = firstSeenTimesFor([routeA, createRoute("B", "PREPARING")], firstVisit, AFTER_DEADLINE + DAY);

      expect(times.has("B")).toBe(false);
      expect(newRouteIdsFor(times, AFTER_DEADLINE + DAY)).toEqual(new Set());
    });

    it("준비 중이던 노선이 서비스 중이 되면 그때 처음 본 것으로 적어 NEW다", () => {
      const firstVisit = firstSeenTimesFor([routeA, createRoute("B", "PREPARING")], NO_RECORD, AFTER_DEADLINE);
      const whilePreparing = firstSeenTimesFor(
        [routeA, createRoute("B", "PREPARING")],
        firstVisit,
        AFTER_DEADLINE + DAY,
      );
      const inService = firstSeenTimesFor([routeA, routeB], whilePreparing, AFTER_DEADLINE + 9 * DAY);

      expect(newRouteIdsFor(inService, AFTER_DEADLINE + 9 * DAY)).toEqual(new Set(["B"]));
    });

    it("적어 둔 노선이 잠깐 준비 중이 됐다가 돌아와도 다시 NEW가 되지 않는다", () => {
      const firstVisit = firstSeenTimesFor([routeA], NO_RECORD, AFTER_DEADLINE);
      const added = firstSeenTimesFor([routeA, routeB], firstVisit, AFTER_DEADLINE + DAY);
      const whilePreparing = firstSeenTimesFor(
        [routeA, createRoute("B", "PREPARING")],
        added,
        AFTER_DEADLINE + 2 * DAY,
      );
      const returned = firstSeenTimesFor([routeA, routeB], whilePreparing, AFTER_DEADLINE + 9 * DAY);

      expect(newRouteIdsFor(returned, AFTER_DEADLINE + 9 * DAY)).toEqual(new Set());
    });
  });
});

describe("newRouteIdsFor", () => {
  const seenAt = AFTER_DEADLINE + DAY;
  const times = firstSeenTimesFor([routeA, routeB], firstSeenTimesFor([routeA], NO_RECORD, AFTER_DEADLINE), seenAt);

  it("첫 방문 때 있던 노선은 NEW가 아니고, 그 뒤에 처음 본 노선은 NEW다", () => {
    expect(newRouteIdsFor(times, seenAt)).toEqual(new Set(["B"]));
  });

  it("처음 본 지 7일이 안 됐으면 NEW다", () => {
    expect(newRouteIdsFor(times, seenAt + 7 * DAY - 1)).toEqual(new Set(["B"]));
  });

  it("처음 본 지 정확히 7일이 되면 NEW가 아니다", () => {
    expect(newRouteIdsFor(times, seenAt + 7 * DAY)).toEqual(new Set());
  });

  it("처음 본 시각이 지금보다 뒤면(기기 시계가 뒤로 감) NEW가 아니다", () => {
    expect(newRouteIdsFor(times, seenAt - 1)).toEqual(new Set());
  });
});
