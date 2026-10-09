import { describe, expect, it } from "@jest/globals";
import type { Event } from "@sentry/react";
import {
  utf8ByteLengthFrom,
  createRateLimiter,
  eventKeyFor,
  EVENT_LIMIT_BYTES,
  RESPONSE_BODY_LIMIT_BYTES,
  eventWithinLimitFor,
  eventWithContextFor,
  responseBodyWithinLimitFor,
} from "./eventPolicy";

describe("오류 전송 정책", () => {
  it("자동 이벤트에서도 인증 정보를 제거하고 요청 자체의 분류 정보를 우선한다", () => {
    const original: Event = {
      user: { id: "secret-user" },
      request: {
        url: "https://example.com/resource?token=secret-query",
        cookies: { session: "secret-cookie" },
        headers: { Authorization: "Bearer secret-header" },
        data: "secret-request-body",
      },
      tags: { resource_id: "request-resource" },
    };
    const result = eventWithContextFor(
      original,
      {
        path: "/screen?token=secret-page",
        tags: { resource_id: "page-resource", auth_token: "secret-token" },
      },
      "test-browser",
    );
    expect(result.extra).toMatchObject({ page_path: "/screen", browser_user_agent: "test-browser" });
    expect(result.tags?.resource_id).toBe("request-resource");
    expect(result.request).toEqual({ url: "https://example.com/resource", headers: { "User-Agent": "test-browser" } });
    expect(JSON.stringify(result)).not.toContain("secret-");
    expect(original.user?.id).toBe("secret-user");
  });

  it.each([
    ["123456789", "123456789"],
    ["000000000", "000000000"],
    ["12345678", "invalid"],
    ["1234567890", "invalid"],
    ["abc", "invalid"],
    ["１２３４５６７８９", "invalid"],
    ["x".repeat(1000), "invalid"],
  ])("페이지의 노선 태그가 9자리 숫자 형식을 따르도록 정규화한다 (%#)", (routeId, expected) => {
    const result = eventWithContextFor<Event>(
      {},
      { path: "/routes/" + routeId, tags: { route_id: routeId } },
      "test-browser",
    );
    expect(result.tags?.route_id).toBe(expected);
    expect(result.extra?.page_path).toBe("/routes/" + encodeURIComponent(routeId));
  });

  it.each([
    ["987654321", "987654321"],
    ["abc", "invalid"],
    [123456789, "invalid"],
  ] as const)("API 요청의 노선 ID %s를 페이지보다 우선하고 최종 태그를 %s로 정규화한다", (routeId, expected) => {
    const original: Event = {
      tags: { source: "api", route_id: routeId },
      extra: { api_path: "/api/routes/" + routeId },
    };
    const result = eventWithContextFor(
      original,
      { path: "/routes/123456789", tags: { route_id: "123456789" } },
      "test-browser",
    );
    expect(result.tags).toEqual({ source: "api", route_id: expected });
    expect(result.extra).toMatchObject({ api_path: "/api/routes/" + routeId, page_path: "/routes/123456789" });
    expect(original.tags?.route_id).toBe(routeId);
  });

  it("노선 정보가 없는 오류에는 노선 태그를 추가하지 않는다", () => {
    const result = eventWithContextFor<Event>({ tags: { react_error_kind: "caught" } }, { path: "/" }, "test-browser");
    expect(result.tags).toEqual({ react_error_kind: "caught" });
  });

  it("같은 탭을 새로고침해도 1분 제한을 이어가고 만료하면 다시 허용한다", () => {
    const values = new Map<string, string>();
    const storage = {
      getItem: (key: string) => values.get(key) ?? null,
      setItem: (key: string, value: string) => {
        values.set(key, value);
      },
    };
    expect(createRateLimiter(60_000, storage)("same", 100_000)).toBe(true);
    const reloaded = createRateLimiter(60_000, storage);
    expect(reloaded("same", 100_100)).toBe(false);
    expect(reloaded("same", 160_000)).toBe(true);
  });
  it("억제한 반복이 1분 창을 연장하지 않고 요청/오류별로 제한한다", () => {
    const allow = createRateLimiter();
    expect(allow("board:route1:503", 0)).toBe(true);
    expect(allow("board:route1:503", 59_999)).toBe(false);
    expect(allow("board:route2:503", 59_999)).toBe(true);
    expect(allow("board:route1:500", 59_999)).toBe(true);
    expect(allow("board:route1:503", 60_000)).toBe(true);
  });

  it("발생마다 다른 requestId는 중복 판정에 쓰지 않는다", () => {
    const base: Event = { tags: { source: "api" }, fingerprint: ["api", "board", "503"] };
    const first = { ...base, extra: { api_path: "/routes/1", request_id: "one" } };
    expect(eventKeyFor(first)).toBe(eventKeyFor({ ...first, extra: { ...first.extra, request_id: "two" } }));
    expect(eventKeyFor(first)).not.toBe(eventKeyFor({ ...first, extra: { api_path: "/routes/2" } }));
  });

  it.each([
    ["한글 한 글자를 온전히 남긴다", "한글", "한"],
    ["공간이 부족한 이모지를 통째로 제외한다", "🚍", ""],
  ])("32KiB 경계에서 %s", (_description, suffix, retained) => {
    const prefix = "x".repeat(RESPONSE_BODY_LIMIT_BYTES - 3);
    const result = responseBodyWithinLimitFor(prefix + suffix);
    expect(result).toEqual({ body: prefix + retained, bytes: utf8ByteLengthFrom(prefix + retained), truncated: true });
    expect(result.bytes).toBeLessThanOrEqual(RESPONSE_BODY_LIMIT_BYTES);
  });

  it("본문의 잘림 여부를 유지하고 최종 전송 본문의 바이트 수를 기록한다", () => {
    const original = { message: "cause", extra: { response_body: '한글🚍"\n', response_body_truncated: true } };
    const result = eventWithinLimitFor(original);
    expect(result).toMatchObject(original);
    expect(result?.extra.response_body_sent_bytes).toBe(utf8ByteLengthFrom(original.extra.response_body));
    expect(original.extra).not.toHaveProperty("response_body_sent_bytes");
  });

  it("SDK 부가정보와 JSON 이스케이프를 포함한 전체 이벤트가 한도 이상이면 전송하지 않는다", () => {
    expect(eventWithinLimitFor({ message: "x".repeat(EVENT_LIMIT_BYTES) })).toBeNull();
    expect(
      eventWithinLimitFor({ extra: { response_body: "\n".repeat(200), diagnostics: "x".repeat(200) } }, 512),
    ).toBeNull();
  });
});
