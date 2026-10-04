import { describe, expect, it } from "@jest/globals";
import type { Event } from "@sentry/react";
import {
  utf8ByteLengthFrom,
  createRateLimiter,
  eventKeyFor,
  EVENT_LIMIT_BYTES,
  eventWithinLimitFor,
  eventWithContextFor,
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

  it("16KB를 넘어도 전체 이벤트가 한도 이하면 본문을 전부 보존한다", () => {
    const body = "정상적인 오류 진단 내용".repeat(10_000);
    const result = eventWithinLimitFor({ extra: { response_body: body } });
    expect(result?.extra.response_body).toBe(body);
    expect(result?.extra.response_body_truncated).toBe(false);
  });

  it("한글/이모지/따옴표의 UTF-8·JSON 크기와 부가정보를 함께 계산한다", () => {
    const body = '한글🚍"\n'.repeat(100_000);
    const original = {
      message: "gateway error",
      extra: {
        response_body: body,
        response_body_original_bytes: utf8ByteLengthFrom(body),
        other: "diagnostics".repeat(2000),
      },
      breadcrumbs: [{ message: "previous step".repeat(1000) }],
    };
    const result = eventWithinLimitFor(original);
    expect(result).not.toBeNull();
    expect(utf8ByteLengthFrom(JSON.stringify(result))).toBeLessThan(EVENT_LIMIT_BYTES);
    expect(result?.extra.response_body_truncated).toBe(true);
    expect(result?.extra.response_body_original_bytes).toBe(utf8ByteLengthFrom(body));
    expect(body.startsWith(result?.extra.response_body ?? "missing")).toBe(true);
    expect(result?.extra.response_body).not.toMatch(/[\uD800-\uDBFF]$/);
    expect(original.extra.response_body).toBe(body);
  });

  it("본문만 줄이고 나머지 원인 정보는 보존하며 크기를 최대한 활용한다", () => {
    const event = { message: "cause", extra: { response_body: "x".repeat(2000) } };
    const fitted = eventWithinLimitFor(event, 512);
    expect(fitted?.message).toBe("cause");
    expect(utf8ByteLengthFrom(JSON.stringify(fitted))).toBe(511);
  });

  it("본문을 전부 빼도 한도를 넘는 이벤트는 전송하지 않는다", () => {
    expect(eventWithinLimitFor({ message: "x".repeat(600), extra: { response_body: "body" } }, 512)).toBeNull();
    expect(eventWithinLimitFor({ message: "x".repeat(600) }, 512)).toBeNull();
  });
});
