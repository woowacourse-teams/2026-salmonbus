import { describe, expect, it } from "@jest/globals";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { routeSelectedPropertiesOf, unmatchedQueryOf } from "./searchEventPolicy";

const route: RouteSummary = {
  id: "204000057",
  displayName: "3330",
  startStopName: "기점",
  endStopName: "종점",
  status: "FORECAST_READY",
};

describe("routeSelectedPropertiesOf", () => {
  it("고른 노선과 검색 결과 안 위치, 결과 개수를 담는다", () => {
    expect(routeSelectedPropertiesOf(route, 0, 3)).toEqual({
      route_id: "204000057",
      route_status: "FORECAST_READY",
      result_position: 0,
      result_count: 3,
    });
  });
});

describe("unmatchedQueryOf", () => {
  it("결과 없음이면 숫자만 남긴 검색어를 보낸다", () => {
    expect(unmatchedQueryOf({ kind: "unmatched" }, "7100")).toBe("7100");
  });

  it("번이 붙어도 숫자만 보낸다", () => {
    expect(unmatchedQueryOf({ kind: "unmatched" }, "71번")).toBe("71");
  });

  it("숫자가 없는 입력은 보내지 않는다", () => {
    expect(unmatchedQueryOf({ kind: "unmatched" }, "번")).toBeNull();
  });

  it("일치하는 노선이 있으면 보내지 않는다", () => {
    expect(unmatchedQueryOf({ kind: "matched", routes: [route] }, "3330")).toBeNull();
  });

  it("목록을 받는 중이면 보내지 않는다", () => {
    expect(unmatchedQueryOf({ kind: "loading" }, "7100")).toBeNull();
  });

  it("목록 조회에 실패하면 보내지 않는다", () => {
    expect(unmatchedQueryOf({ kind: "error" }, "7100")).toBeNull();
  });
});
