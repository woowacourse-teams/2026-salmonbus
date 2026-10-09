import { describe, expect, it, jest } from "@jest/globals";
import * as Sentry from "@sentry/react";
import type { Event, Breadcrumb } from "@sentry/react";
import { initErrorTracking, createApiFailureReporter, createReactErrorHandlers } from "./errorTracking";
import { REQUEST_DISPOSED, REQUEST_SUPERSEDED } from "@/shared/api/cancellation";

jest.mock("@sentry/react", () => ({
  ...jest.requireActual<typeof import("@sentry/react")>("@sentry/react"),
  init: jest.fn<typeof Sentry.init>(),
}));

function createRecording() {
  const events: Event[] = [];
  const errors: Error[] = [];
  const breadcrumbs: Breadcrumb[] = [];
  const report = createApiFailureReporter(
    (error, event) => {
      errors.push(error);
      events.push(event);
    },
    (breadcrumb) => breadcrumbs.push(breadcrumb),
  );
  return { report, events, errors, breadcrumbs };
}

describe("공통 오류 수집", () => {
  it("SDK 기본 통합을 끄고 허용한 통합만 초기화한다", () => {
    const init = jest.mocked(Sentry.init);
    initErrorTracking({
      dsn: "",
      environment: "production",
      release: "test",
      contextForPage: () => ({ path: "/" }),
    });

    const options = init.mock.calls[0]?.[0];
    expect(options?.defaultIntegrations).toBe(false);
    expect(Array.isArray(options?.integrations)).toBe(true);
    if (!Array.isArray(options?.integrations)) throw new Error("명시적인 통합 목록이 필요합니다");
    expect(options.integrations.map(({ name }) => name)).toEqual([
      "EventFilters",
      "FunctionToString",
      "BrowserApiErrors",
      "GlobalHandlers",
      "LinkedErrors",
      "HttpContext",
      "CultureContext",
      "Breadcrumbs",
    ]);
  });

  it.each([REQUEST_DISPOSED, REQUEST_SUPERSEDED])("확인된 취소는 참고 기록만 남긴다", (abortReason) => {
    const recording = createRecording();
    recording.report("/api/resource", { kind: "aborted" }, { context: null, abortReason, rawResponse: null });
    expect(recording.events).toEqual([]);
    expect(recording.breadcrumbs).toEqual([
      expect.objectContaining({
        category: "request.cancelled",
        data: expect.objectContaining({ api_path: "/api/resource" }),
      }),
    ]);
  });

  it("이유 없는 취소는 오류로 남기고 취소 원인 객체는 첨부하지 않는다", () => {
    const recording = createRecording();
    recording.report(
      "/api/resource",
      { kind: "aborted" },
      { context: null, abortReason: { token: "secret" }, rawResponse: null },
    );
    expect(recording.events[0]?.extra?.cancellation_reason).toBe("unknown");
    expect(JSON.stringify(recording.events)).not.toContain("secret");
  });

  it("오류 응답의 세 항목과 호출자가 제공한 분류 정보를 전달한다", () => {
    const recording = createRecording();
    recording.report(
      "/api/resource/42",
      {
        kind: "contract",
        status: 503,
        retryAfterMs: null,
        error: { code: "SERVICE_UNAVAILABLE", message: "일시적 장애", requestId: "request-1" },
      },
      {
        context: { endpoint: "/api/resource/:id", tags: { resource_id: "42" } },
        abortReason: null,
        rawResponse: { body: '{"token":"secret"}', contentType: null },
      },
    );
    expect(recording.events[0]).toMatchObject({
      fingerprint: ["api", "/api/resource/:id", "contract", "503", "SERVICE_UNAVAILABLE"],
      tags: { resource_id: "42", error_code: "SERVICE_UNAVAILABLE" },
      extra: { error_message: "일시적 장애", request_id: "request-1", http_status: 503 },
    });
    expect(recording.events[0]?.extra).not.toHaveProperty("response_body");
    expect(recording.events[0]?.tags).not.toHaveProperty("route_id");
    expect(recording.errors[0]?.stack).toBeDefined();
  });

  it.each(["network", "timeout", "rateLimited"] as const)("%s 실패도 수집한다", (kind) => {
    const recording = createRecording();
    const failure =
      kind === "rateLimited"
        ? { kind, status: 429, requestId: "request-429", retryAfterMs: 1000 }
        : kind === "network"
          ? { kind, cause: new TypeError("Failed to fetch") }
          : { kind };
    recording.report("/api/resource", failure, { context: null, abortReason: null, rawResponse: null });
    expect(recording.events[0]?.tags?.failure_kind).toBe(kind);
    expect(recording.events[0]?.fingerprint?.[1]).toBe("/api/resource");
  });

  it("형식 오류 본문은 인증 정보를 가린 후 길이 제한 없이 수집 단계로 전달한다", () => {
    const recording = createRecording();
    const explanation = "진단 내용".repeat(5000);
    recording.report(
      "/api/resource",
      { kind: "malformed", status: 502, requestId: null },
      {
        context: null,
        abortReason: null,
        rawResponse: {
          body: JSON.stringify({ access_token: "secret", explanation }),
          contentType: "application/json",
        },
      },
    );
    expect(String(recording.events[0]?.extra?.response_body)).toContain(explanation);
    expect(JSON.stringify(recording.events)).not.toContain("secret");
    expect(recording.events[0]?.extra?.response_body_original_bytes).toBeGreaterThan(16_384);
    expect(recording.events[0]?.extra?.response_content_type).toBe("application/json");
  });

  it("React 콜백별 실제 처리 상태와 컴포넌트 스택을 전달한다", () => {
    const captures: unknown[][] = [];
    const handlers = createReactErrorHandlers((...args) => {
      captures.push(args);
      return "event-id";
    });
    const error = new Error("화면 오류");
    handlers.onCaughtError?.(error, { componentStack: "component-stack" });
    handlers.onUncaughtError?.(error, {});
    handlers.onRecoverableError?.(error, {});
    expect(captures[0]).toEqual([
      error,
      { componentStack: "component-stack" },
      expect.objectContaining({
        mechanism: expect.objectContaining({ handled: true }),
        captureContext: { tags: { react_error_kind: "caught" } },
      }),
    ]);
    expect(captures[1]?.[2]).toMatchObject({ mechanism: { handled: false } });
    expect(captures[2]?.[2]).toMatchObject({
      mechanism: { handled: true },
      captureContext: { tags: { react_error_kind: "recoverable" } },
    });
  });
});
