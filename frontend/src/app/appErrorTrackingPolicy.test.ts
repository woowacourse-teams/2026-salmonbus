import { describe, expect, it } from "@jest/globals";
import { isErrorTrackingEnabled, pageErrorContextFor } from "./appErrorTrackingPolicy";

describe("앱 오류 추적 설정", () => {
  it.each([
    ["development", "production", "dsn", "www.salmonbus.com", false],
    ["test", "production", "dsn", "www.salmonbus.com", false],
    ["production", "", "dsn", "www.salmonbus.com", false],
    ["production", "production", "", "www.salmonbus.com", false],
    ["production", "production", "dsn", "localhost", false],
    ["production", "production", "dsn", "127.0.0.1", false],
    ["production", "production", "dsn", "www.salmonbus.com", true],
  ] as const)("%s / %s / %s / %s 에서 수집 여부는 %s다", (mode, environment, dsn, hostname, expected) => {
    expect(isErrorTrackingEnabled(mode, environment, dsn, hostname)).toBe(expected);
  });

  it("앱의 라우트 정의로 현재 화면의 노선을 결정한다", () => {
    expect(pageErrorContextFor("/routes/123456789")).toEqual({
      path: "/routes/123456789",
      tags: { route_id: "123456789" },
    });
    expect(pageErrorContextFor("/")).toEqual({ path: "/" });
    expect(pageErrorContextFor("/other")).toEqual({ path: "/other" });
  });
});
