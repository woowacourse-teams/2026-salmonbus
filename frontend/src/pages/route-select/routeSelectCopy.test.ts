import { describe, expect, it } from "@jest/globals";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { searchStatusLabel } from "./routeSelectCopy";

const route: RouteSummary = {
  id: "5600",
  displayName: "5600",
  startStopName: "기점",
  endStopName: "종점",
  status: "FORECAST_READY",
};

describe("searchStatusLabel", () => {
  it("입력 전에는 알릴 것이 없다", () => {
    expect(searchStatusLabel({ kind: "idle" })).toBe("");
  });

  it("목록을 받는 중이면 불러오는 중이라고 알린다", () => {
    expect(searchStatusLabel({ kind: "loading" })).toBe("노선을 불러오는 중이에요");
  });

  it("목록 조회에 실패하면 실패를 알린다", () => {
    expect(searchStatusLabel({ kind: "error" })).toBe("노선을 불러오지 못했어요");
  });

  it("일치하는 노선이 없으면 결과 없음을 알린다", () => {
    expect(searchStatusLabel({ kind: "unmatched" })).toBe("일치하는 노선이 없어요");
  });

  it("일치하는 노선이 있으면 개수를 알린다", () => {
    expect(searchStatusLabel({ kind: "matched", routes: [route] })).toBe("일치하는 노선 1개");
  });
});
