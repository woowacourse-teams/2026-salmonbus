import { describe, expect, it } from "@jest/globals";
import type { ListedRoute } from "../displayPolicy";
import { routeSelectedPropertiesOf, unmatchedQueryOf } from "./searchEventPolicy";

const route: ListedRoute = {
  id: "204000057",
  displayName: "3330",
  startStopName: "기점",
  endStopName: "종점",
  status: "FORECAST_READY",
  isNew: false,
};

describe("routeSelectedPropertiesOf", () => {
  it("고른 노선과 목록 안 위치, 목록 개수, 친 숫자 자릿수를 담는다", () => {
    expect(routeSelectedPropertiesOf({ route, position: 0, listedCount: 3 }, "33")).toEqual({
      route_id: "204000057",
      route_status: "FORECAST_READY",
      is_new: false,
      result_position: 0,
      result_count: 3,
      query_length: 2,
    });
  });

  it("새 노선을 고르면 새 노선이라고 담는다", () => {
    expect(
      routeSelectedPropertiesOf({ route: { ...route, isNew: true }, position: 1, listedCount: 3 }, "3").is_new,
    ).toBe(true);
  });

  it("입력 전 목록에서 고르면 친 숫자 자릿수가 0이다", () => {
    expect(routeSelectedPropertiesOf({ route, position: 7, listedCount: 20 }, "").query_length).toBe(0);
  });

  it("공백만 친 입력에서 고르면 친 숫자 자릿수가 0이다", () => {
    expect(routeSelectedPropertiesOf({ route, position: 7, listedCount: 20 }, "  ").query_length).toBe(0);
  });

  it("번이 붙은 입력은 숫자만 센다", () => {
    expect(routeSelectedPropertiesOf({ route, position: 0, listedCount: 1 }, "3330번").query_length).toBe(4);
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
