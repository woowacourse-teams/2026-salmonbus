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

  it.each([
    ["/routes/123456789", "123456789"],
    ["/routes/123456789/", "123456789"],
    ["/routes/123456789//", "123456789"],
    ["/ROUTES/123456789", "123456789"],
    ["/routes/%31%32%33", "123"],
    ["/routes/a%2Fb", "a/b"],
  ])("%s에서 라우터가 해석한 노선 ID %s를 수집한다", (pathname, routeId) => {
    expect(pageErrorContextFor(pathname)).toEqual({
      path: pathname,
      tags: { route_id: routeId },
    });
  });

  it.each(["/", "/other", "/routes/", "/routes/123456789/other"])(
    "%s가 판정보드 경로와 일치하지 않으면 노선 태그를 넣지 않는다",
    (pathname) => {
      expect(pageErrorContextFor(pathname)).toEqual({ path: pathname });
    },
  );
});
