import { describe, expect, it } from "@jest/globals";
import type { RouteStatus, RouteSummary } from "@/shared/api/routeForecast.types";
import { firstSeenTimesFor, newRouteIdsFor, type FirstSeenTimes } from "./newRoutePolicy";

const DEADLINE = Date.parse("2026-12-01T00:00:00+09:00");
const BEFORE_DEADLINE = Date.parse("2026-10-27T08:00:00+09:00");
const DAY = 24 * 60 * 60_000;
const NO_RECORD: FirstSeenTimes = new Map();
const ROUTE_1650 = "234000050";
const ROUTE_3330 = "204000057";

function createRoute(id: string, status: RouteStatus = "FORECAST_READY"): RouteSummary {
  return { id, displayName: id, startStopName: "기점", endStopName: "종점", status };
}

describe("출시 기간에 처음 온 기기", () => {
  it("1650번과 3330번 말고 서비스 중인 노선은 모두 NEW다", () => {
    const routes = [createRoute(ROUTE_1650), createRoute(ROUTE_3330), createRoute("9300"), createRoute("5600")];

    const times = firstSeenTimesFor(routes, NO_RECORD, BEFORE_DEADLINE);

    expect(newRouteIdsFor(times, BEFORE_DEADLINE)).toEqual(new Set(["9300", "5600"]));
  });

  it("1650번이 준비 중이고 3330번이 목록에 없어도 원래 알던 노선으로 적어, 나중에 서비스 중이 돼도 NEW가 아니다", () => {
    const firstVisit = firstSeenTimesFor(
      [createRoute(ROUTE_1650, "PREPARING"), createRoute("9300")],
      NO_RECORD,
      BEFORE_DEADLINE,
    );
    const later = firstSeenTimesFor(
      [createRoute(ROUTE_1650), createRoute(ROUTE_3330), createRoute("9300")],
      firstVisit,
      BEFORE_DEADLINE + DAY,
    );

    expect(newRouteIdsFor(later, BEFORE_DEADLINE + DAY)).toEqual(new Set(["9300"]));
  });

  it("서비스 중 노선이 하나도 없어도 첫 방문을 끝내, 마감 뒤에 생긴 노선도 NEW다", () => {
    const firstVisit = firstSeenTimesFor([createRoute("9300", "PREPARING")], NO_RECORD, DEADLINE - DAY);
    const later = firstSeenTimesFor([createRoute(ROUTE_3330), createRoute("9300")], firstVisit, DEADLINE + DAY);

    expect(newRouteIdsFor(later, DEADLINE + DAY)).toEqual(new Set(["9300"]));
  });
});

describe("마감 시각 정각에 처음 온 기기", () => {
  it("마감 뒤 규칙을 따라 서비스 중인 노선에 NEW를 붙이지 않는다", () => {
    const times = firstSeenTimesFor([createRoute(ROUTE_1650), createRoute("9300")], NO_RECORD, DEADLINE);

    expect(newRouteIdsFor(times, DEADLINE)).toEqual(new Set());
  });
});
