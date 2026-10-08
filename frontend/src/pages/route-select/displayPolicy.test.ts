import { describe, expect, it } from "@jest/globals";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { searchViewFor, type RoutesState } from "./displayPolicy";

function createRoute(displayName: string): RouteSummary {
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

const loading: RoutesState = { status: "loading" };
const failed: RoutesState = { status: "error" };
const ready: RoutesState = { status: "ready", routes: [route5600, route3330] };

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
    it("입력이 없으면 아무것도 보여주지 않는다", () => {
      expect(searchViewFor(loading, "")).toEqual({ kind: "idle" });
    });

    it("입력이 있으면 결과 없음 대신 불러오는 중을 보여준다", () => {
      expect(searchViewFor(loading, "56")).toEqual({ kind: "loading" });
    });
  });

  describe("목록을 다 받았으면", () => {
    it("입력이 없으면 아무것도 보여주지 않는다", () => {
      expect(searchViewFor(ready, "")).toEqual({ kind: "idle" });
    });

    it("공백만 친 입력도 빈 입력으로 본다", () => {
      expect(searchViewFor(ready, "  ")).toEqual({ kind: "idle" });
    });

    it("앞부분이 일치하는 노선을 보여준다", () => {
      expect(searchViewFor(ready, "56")).toEqual({ kind: "matched", routes: [route5600] });
    });

    it("일치하는 노선이 없으면 결과 없음을 보여준다", () => {
      expect(searchViewFor(ready, "7")).toEqual({ kind: "unmatched" });
    });

    it("숫자가 없는 입력은 결과 없음을 보여준다", () => {
      expect(searchViewFor(ready, "번")).toEqual({ kind: "unmatched" });
    });
  });
});
