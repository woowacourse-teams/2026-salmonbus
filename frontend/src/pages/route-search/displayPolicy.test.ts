import { describe, expect, it } from "@jest/globals";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { searchViewFor, submitSelectionFor, type RoutesState } from "./displayPolicy";
import type { FirstSeenTimes } from "./new-route/newRoutePolicy";
import type { InServiceRoute } from "./routeSearchPolicy";

const FIRST_SEEN_AT = Date.parse("2027-01-10T08:00:00+09:00");
const NEW_PERIOD_MS = 7 * 24 * 60 * 60_000;

function createRoute(displayName: string): InServiceRoute {
  return {
    id: displayName,
    displayName,
    startStopName: "기점",
    endStopName: "종점",
    status: "FORECAST_READY",
  };
}

const route5600 = createRoute("5600");
const route3330 = createRoute("3330");
const preparingRoute3100: RouteSummary = { ...createRoute("3100"), status: "PREPARING" };

function createReadyState(firstSeenTimes: FirstSeenTimes, checkedAt: number): RoutesState {
  return { status: "ready", routes: [route5600, route3330, preparingRoute3100], firstSeenTimes, checkedAt };
}

const loading: RoutesState = { status: "loading" };
const failed: RoutesState = { status: "error" };
const ready = createReadyState(new Map(), FIRST_SEEN_AT);

describe("searchViewFor", () => {
  describe("목록 조회에 실패하면", () => {
    it("입력이 없어도 실패를 보여준다", () => {
      expect(searchViewFor(failed, "")).toEqual({ kind: "error" });
    });

    it("입력이 있어도 실패를 보여준다", () => {
      expect(searchViewFor(failed, "56")).toEqual({ kind: "error" });
    });
  });

  describe("목록을 받는 중이면", () => {
    it("입력이 없어도 노선 목록 대신 불러오는 중을 보여준다", () => {
      expect(searchViewFor(loading, "")).toEqual({ kind: "loading" });
    });

    it("입력이 있으면 결과 없음 대신 불러오는 중을 보여준다", () => {
      expect(searchViewFor(loading, "56")).toEqual({ kind: "loading" });
    });
  });

  describe("목록을 다 받았으면", () => {
    it("입력이 없으면 서비스 중 노선 전체를 번호 오름차순으로 보여준다", () => {
      expect(searchViewFor(ready, "")).toEqual({
        kind: "all",
        routes: [
          { ...route3330, isNew: false },
          { ...route5600, isNew: false },
        ],
      });
    });

    it("공백만 친 입력도 빈 입력으로 본다", () => {
      expect(searchViewFor(ready, "  ")).toEqual(searchViewFor(ready, ""));
    });

    it("입력이 없을 때도 새 노선으로 판단한 노선에만 새 노선 표시를 붙인다", () => {
      const withNewRoute = createReadyState(new Map([["3330", FIRST_SEEN_AT]]), FIRST_SEEN_AT);

      expect(searchViewFor(withNewRoute, "")).toEqual({
        kind: "all",
        routes: [
          { ...route3330, isNew: true },
          { ...route5600, isNew: false },
        ],
      });
    });

    it("입력이 없는데 서비스 중 노선이 하나도 없으면 안내를 보여준다", () => {
      const onlyPreparing: RoutesState = {
        status: "ready",
        routes: [preparingRoute3100],
        firstSeenTimes: new Map(),
        checkedAt: FIRST_SEEN_AT,
      };

      expect(searchViewFor(onlyPreparing, "")).toEqual({ kind: "empty" });
    });

    it("앞부분이 일치하는 노선을 보여준다", () => {
      expect(searchViewFor(ready, "56")).toEqual({ kind: "matched", routes: [{ ...route5600, isNew: false }] });
    });

    it("일치하는 노선을 응답 순서가 아니라 번호 오름차순으로 보여준다", () => {
      const route3000 = createRoute("3000");
      const state: RoutesState = {
        status: "ready",
        routes: [route3330, route5600, route3000],
        firstSeenTimes: new Map(),
        checkedAt: FIRST_SEEN_AT,
      };

      expect(searchViewFor(state, "3")).toEqual({
        kind: "matched",
        routes: [
          { ...route3000, isNew: false },
          { ...route3330, isNew: false },
        ],
      });
    });

    it("준비 중 노선은 앞부분이 일치해도 보여주지 않는다", () => {
      expect(searchViewFor(ready, "3")).toEqual({ kind: "matched", routes: [{ ...route3330, isNew: false }] });
    });

    it("준비 중 노선만 일치하면 결과 없음을 보여준다", () => {
      expect(searchViewFor(ready, "31")).toEqual({ kind: "unmatched" });
    });

    it("새 노선으로 판단한 노선에만 새 노선 표시를 붙인다", () => {
      const withNewRoute = createReadyState(
        new Map([
          ["5600", 0],
          ["3330", FIRST_SEEN_AT],
        ]),
        FIRST_SEEN_AT,
      );

      expect(searchViewFor(withNewRoute, "3")).toEqual({ kind: "matched", routes: [{ ...route3330, isNew: true }] });
      expect(searchViewFor(withNewRoute, "56")).toEqual({ kind: "matched", routes: [{ ...route5600, isNew: false }] });
    });

    it("처음 본 지 7일이 되기 직전에 확인하면 새 노선 표시를 붙인다", () => {
      const beforeExpiry = createReadyState(new Map([["3330", FIRST_SEEN_AT]]), FIRST_SEEN_AT + NEW_PERIOD_MS - 1);

      expect(searchViewFor(beforeExpiry, "3")).toEqual({ kind: "matched", routes: [{ ...route3330, isNew: true }] });
    });

    it("처음 본 지 7일이 된 시각에 확인하면 새 노선 표시를 붙이지 않는다", () => {
      const atExpiry = createReadyState(new Map([["3330", FIRST_SEEN_AT]]), FIRST_SEEN_AT + NEW_PERIOD_MS);

      expect(searchViewFor(atExpiry, "3")).toEqual({ kind: "matched", routes: [{ ...route3330, isNew: false }] });
    });

    it("일치하는 노선이 없으면 결과 없음을 보여준다", () => {
      expect(searchViewFor(ready, "7")).toEqual({ kind: "unmatched" });
    });

    it("숫자가 없는 입력은 결과 없음을 보여준다", () => {
      expect(searchViewFor(ready, "번")).toEqual({ kind: "unmatched" });
    });
  });
});

describe("submitSelectionFor", () => {
  it("입력 전에는 엔터 대상이 없다", () => {
    expect(submitSelectionFor({ kind: "empty" })).toBeNull();
  });

  it("입력 전 목록에 노선이 하나뿐이어도 엔터 대상이 없다", () => {
    expect(submitSelectionFor({ kind: "all", routes: [{ ...route5600, isNew: false }] })).toBeNull();
  });

  it("결과가 정확히 1개면 그 노선이 목록 첫 번째 자리의 엔터 대상이다", () => {
    expect(submitSelectionFor({ kind: "matched", routes: [{ ...route5600, isNew: false }] })).toEqual({
      route: { ...route5600, isNew: false },
      position: 0,
      listedCount: 1,
    });
  });

  it("엔터 대상은 목록에 붙은 새 노선 표시를 그대로 가진다", () => {
    expect(submitSelectionFor({ kind: "matched", routes: [{ ...route5600, isNew: true }] })?.route.isNew).toBe(true);
  });

  it("결과가 2개 이상이면 엔터 대상이 없다", () => {
    expect(
      submitSelectionFor({
        kind: "matched",
        routes: [
          { ...route5600, isNew: false },
          { ...route3330, isNew: false },
        ],
      }),
    ).toBeNull();
  });

  it("일치하는 노선이 없으면 엔터 대상이 없다", () => {
    expect(submitSelectionFor({ kind: "unmatched" })).toBeNull();
  });
});
