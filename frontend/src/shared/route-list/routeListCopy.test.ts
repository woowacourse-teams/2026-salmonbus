import { describe, expect, it } from "@jest/globals";
import type { ListedRoute } from "./newRoutePolicy";
import { routeButtonLabelOf } from "./routeListCopy";

const route: ListedRoute = {
  id: "5600",
  displayName: "5600",
  startStopName: "기점",
  endStopName: "종점",
  status: "FORECAST_READY",
  isNew: false,
};

describe("routeButtonLabelOf", () => {
  it("노선 번호와 기점, 종점 구간을 읽어 준다", () => {
    expect(routeButtonLabelOf(route)).toBe("5600번 노선, 기점부터 종점 구간");
  });

  it("새 노선이면 번호 다음에 새 노선이라고 읽어 준다", () => {
    expect(routeButtonLabelOf({ ...route, isNew: true })).toBe("5600번 노선, 새 노선, 기점부터 종점 구간");
  });
});
