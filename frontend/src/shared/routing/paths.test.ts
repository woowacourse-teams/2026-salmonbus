import { describe, expect, it } from "@jest/globals";
import { favoritePath } from "./paths";

describe("즐겨찾기 경로", () => {
  it("노선 주소에 방면과 정류장 ID를 포함한다", () => {
    expect(favoritePath({ routeId: "200000001", stopId: "200000011", direction: "UP" })).toBe(
      "/routes/200000001?direction=UP&stopId=200000011",
    );
  });
});
