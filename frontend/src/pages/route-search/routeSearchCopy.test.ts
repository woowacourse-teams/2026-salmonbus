import { describe, expect, it } from "@jest/globals";
import type { ListedRoute } from "@/shared/route-list/newRoutePolicy";
import { searchStatusLabelOf } from "./routeSearchCopy";

const route: ListedRoute = {
  id: "5600",
  displayName: "5600",
  startStopName: "기점",
  endStopName: "종점",
  status: "FORECAST_READY",
  isNew: false,
};

describe("searchStatusLabelOf", () => {
  it("서비스 중 노선이 하나도 없으면 볼 수 있는 노선이 없다고 알린다", () => {
    expect(searchStatusLabelOf({ kind: "empty" })).toBe("지금 볼 수 있는 노선이 없어요. 잠시 뒤에 다시 불러와 주세요.");
  });

  it("목록을 받는 중이면 불러오는 중이라고 알린다", () => {
    expect(searchStatusLabelOf({ kind: "loading" })).toBe("노선을 불러오는 중이에요");
  });

  it("목록 조회에 실패하면 실패를 알린다", () => {
    expect(searchStatusLabelOf({ kind: "error" })).toBe("노선을 불러오지 못했어요");
  });

  it("입력 전 목록이면 서비스 중 노선 개수를 알린다", () => {
    expect(searchStatusLabelOf({ kind: "all", routes: [route, { ...route, id: "3330" }] })).toBe("서비스 중 노선 2개");
  });

  it("일치하는 노선이 없으면 결과 없음을 알린다", () => {
    expect(searchStatusLabelOf({ kind: "unmatched" })).toBe("일치하는 노선이 없어요");
  });

  it("일치하는 노선이 있으면 개수를 알린다", () => {
    expect(searchStatusLabelOf({ kind: "matched", routes: [route] })).toBe("일치하는 노선 1개");
  });
});
