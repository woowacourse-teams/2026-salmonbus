import { describe, expect, it } from "@jest/globals";
import type { ListedRoute } from "./displayPolicy";
import { routeButtonLabel, searchStatusLabel } from "./routeSearchCopy";

const route: ListedRoute = {
  id: "5600",
  displayName: "5600",
  startStopName: "기점",
  endStopName: "종점",
  status: "FORECAST_READY",
  isNew: false,
};

describe("searchStatusLabel", () => {
  it("서비스 중 노선이 하나도 없으면 볼 수 있는 노선이 없다고 알린다", () => {
    expect(searchStatusLabel({ kind: "empty" })).toBe("지금 볼 수 있는 노선이 없어요. 잠시 뒤에 다시 불러와 주세요.");
  });

  it("목록을 받는 중이면 불러오는 중이라고 알린다", () => {
    expect(searchStatusLabel({ kind: "loading" })).toBe("노선을 불러오는 중이에요");
  });

  it("목록 조회에 실패하면 실패를 알린다", () => {
    expect(searchStatusLabel({ kind: "error" })).toBe("노선을 불러오지 못했어요");
  });

  it("입력 전 목록이면 서비스 중 노선 개수를 알린다", () => {
    expect(searchStatusLabel({ kind: "all", routes: [route, { ...route, id: "3330" }] })).toBe("서비스 중 노선 2개");
  });

  it("일치하는 노선이 없으면 결과 없음을 알린다", () => {
    expect(searchStatusLabel({ kind: "unmatched" })).toBe("일치하는 노선이 없어요");
  });

  it("일치하는 노선이 있으면 개수를 알린다", () => {
    expect(searchStatusLabel({ kind: "matched", routes: [route] })).toBe("일치하는 노선 1개");
  });
});

describe("routeButtonLabel", () => {
  it("노선 번호와 기점, 종점 구간을 읽어 준다", () => {
    expect(routeButtonLabel(route)).toBe("5600번 노선, 기점부터 종점 구간");
  });

  it("새 노선이면 번호 다음에 새 노선이라고 읽어 준다", () => {
    expect(routeButtonLabel({ ...route, isNew: true })).toBe("5600번 노선, 새 노선, 기점부터 종점 구간");
  });
});
