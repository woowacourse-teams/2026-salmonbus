import { afterEach, beforeEach, describe, expect, it, jest } from "@jest/globals";
import {
  initApiFailureReporter,
  requestJson,
  type ApiFailure,
  type ApiResult,
  type ApiFailureReporter,
} from "./client";
import { fetchBoard } from "./routeForecast.api";
import { REQUEST_DISPOSED } from "./cancellation";

const reportApiFailure = jest.fn<ApiFailureReporter>();

beforeEach(() => {
  reportApiFailure.mockReset();
  initApiFailureReporter(reportApiFailure);
});

const originalFetch = global.fetch;

afterEach(() => {
  initApiFailureReporter(null);
  global.fetch = originalFetch;
  jest.useRealTimers();
});

function respondWith(status: number, body: unknown, headers: Record<string, string> = {}) {
  global.fetch = jest.fn(async () => new Response(JSON.stringify(body), { status, headers })) as typeof fetch;
}

function respondText(status: number, text: string) {
  global.fetch = jest.fn(async () => new Response(text, { status })) as typeof fetch;
}

function failureOf(result: ApiResult<unknown>): ApiFailure {
  if (result.ok) throw new Error("실패 응답을 기대했지만 성공 응답이 왔다");
  return result.failure;
}

describe("requestJson", () => {
  it("노선 정보는 URL에서 추측하지 않고 API 호출자가 전달한다", async () => {
    respondWith(503, { code: "SERVICE_UNAVAILABLE", message: "장애", requestId: "request-1" });
    await fetchBoard("123456789");
    expect(reportApiFailure).toHaveBeenCalledWith(
      "/api/v1/routes/123456789/board",
      expect.anything(),
      expect.objectContaining({ endpoint: "/api/v1/routes/:routeId/board", tags: { route_id: "123456789" } }),
    );
  });

  it("수집 함수가 실패해도 서버의 원래 오류 응답을 유지한다", async () => {
    reportApiFailure.mockImplementation(() => {
      throw new Error("수집 실패");
    });
    respondWith(503, { code: "SERVICE_UNAVAILABLE", message: "서버 장애", requestId: "request-1" });
    expect(failureOf(await requestJson("/api/v1/routes"))).toMatchObject({
      kind: "contract",
      status: 503,
      error: { message: "서버 장애", requestId: "request-1" },
    });
    expect(reportApiFailure).toHaveBeenCalledTimes(1);
  });

  it("앱에서 수집 함수를 연결하지 않아도 요청은 그대로 동작한다", async () => {
    initApiFailureReporter(null);
    respondWith(500, { code: "INTERNAL_ERROR", message: "오류", requestId: "request-1" });
    expect(failureOf(await requestJson("/api/v1/routes")).kind).toBe("contract");
    expect(reportApiFailure).not.toHaveBeenCalled();
  });
  it.each([
    [400, "INVALID_ROUTE_ID"],
    [400, "INVALID_REQUEST"],
    [404, "ROUTE_NOT_FOUND"],
    [404, "ENDPOINT_NOT_FOUND"],
    [405, "METHOD_NOT_ALLOWED"],
    [500, "INTERNAL_ERROR"],
    [503, "SERVICE_UNAVAILABLE"],
    [503, "MODEL_OUT_OF_SCOPE"],
    [503, "NO_RECENT_OBSERVATION"],
  ])("%s / %s 응답을 바꾸지 않고 한 번 보고한다", async (status, code) => {
    respondWith(Number(status), { code, message: "원래 메시지", requestId: "request-1" });
    const result = await requestJson("/api/v1/routes/123456789/board");
    const failure = failureOf(result);
    expect(failure).toMatchObject({
      kind: "contract",
      error: { code, message: "원래 메시지", requestId: "request-1" },
    });
    expect(reportApiFailure).toHaveBeenCalledTimes(1);
    expect(reportApiFailure).toHaveBeenCalledWith("/api/v1/routes/123456789/board", failure, {
      abortReason: undefined,
    });
  });

  it("정상 응답은 기록하지 않고, 형식 오류일 때만 원문을 보고한다", async () => {
    respondWith(200, { routes: [] });
    await requestJson("/api/v1/routes");
    expect(reportApiFailure).not.toHaveBeenCalled();
    respondText(502, "<html>Bad Gateway</html>");
    await requestJson("/api/v1/routes");
    expect(reportApiFailure).toHaveBeenCalledWith(
      "/api/v1/routes",
      expect.objectContaining({ kind: "malformed", status: 502 }),
      expect.objectContaining({ responseBody: "<html>Bad Gateway</html>" }),
    );
  });

  it("외부 취소의 실제 이유를 전달한다", async () => {
    const controller = new AbortController();
    controller.abort(REQUEST_DISPOSED);
    await requestJson("/api/v1/routes", { signal: controller.signal });
    expect(reportApiFailure).toHaveBeenCalledWith(
      "/api/v1/routes",
      { kind: "aborted" },
      { abortReason: REQUEST_DISPOSED },
    );
  });

  it("연결 실패도 보고한다", async () => {
    global.fetch = jest.fn(async () => {
      throw new TypeError("Failed to fetch");
    }) as typeof fetch;
    const result = await requestJson("/api/v1/routes");
    expect(failureOf(result).kind).toBe("network");
    expect(reportApiFailure).toHaveBeenCalledTimes(1);
  });

  it("타임아웃은 정상 취소와 구분하고 요청 제한 시간을 유지한다", async () => {
    jest.useFakeTimers();
    global.fetch = jest.fn(
      (_url: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason), { once: true });
        }),
    ) as typeof fetch;
    const pending = requestJson("/api/v1/routes");
    await jest.advanceTimersByTimeAsync(10_000);
    expect(failureOf(await pending).kind).toBe("timeout");
    expect(reportApiFailure).toHaveBeenCalledWith("/api/v1/routes", { kind: "timeout" }, { abortReason: undefined });
  });

  describe("오류 응답을 받으면", () => {
    it("429 응답이면 rateLimited로 분류하고 상태 코드를 함께 담는다", async () => {
      respondWith(429, {});
      const failure = failureOf(await requestJson("/api/v1/routes/R1/board"));
      expect(failure).toMatchObject({ kind: "rateLimited", status: 429 });
    });

    it("계약에 있는 code면 retryable 필드 없이도 contract로 분류한다", async () => {
      respondWith(503, { code: "MODEL_OUT_OF_SCOPE", message: "지원하지 않는 판본", requestId: "req-contract" });
      const failure = failureOf(await requestJson("/api/v1/routes/R1/board"));
      expect(failure).toMatchObject({ kind: "contract", status: 503, error: { code: "MODEL_OUT_OF_SCOPE" } });
    });

    it("계약에 없는 code일 경우 malformed로 분류한다", async () => {
      respondWith(500, { code: "SOMETHING_NEW", message: "x", requestId: "req-unknown-code" });
      const failure = failureOf(await requestJson("/api/v1/routes/R1/board"));
      expect(failure).toMatchObject({ kind: "malformed", status: 500 });
    });

    it("서버가 503과 함께 Retry-After 헤더를 보내면 초를 ms로 바꾼다음 retryAfterMs에 넣는다", async () => {
      respondWith(
        503,
        { code: "NO_RECENT_OBSERVATION", message: "최근 관측 없음", requestId: "req-retry-after" },
        { "retry-after": "30" },
      );
      const failure = failureOf(await requestJson("/api/v1/routes/R1/board"));
      expect(failure).toMatchObject({ kind: "contract", status: 503, retryAfterMs: 30_000 });
    });
  });

  describe("본문이 JSON이 아니면", () => {
    it("상태 코드가 200이어도 malformed로 분류한다", async () => {
      respondText(200, "<html>gateway</html>");
      const failure = failureOf(await requestJson("/api/v1/routes"));
      expect(failure).toMatchObject({ kind: "malformed", status: 200 });
    });
  });

  describe("이미 취소된 signal을 받으면", () => {
    it("fetch를 부르지 않고 kind가 aborted인 실패를 리턴한다", async () => {
      const fetchSpy = jest.fn();
      global.fetch = fetchSpy as typeof fetch;
      const controller = new AbortController();
      controller.abort();

      const failure = failureOf(await requestJson("/api/v1/routes", { signal: controller.signal }));
      expect(fetchSpy).not.toHaveBeenCalled();
      expect(failure.kind).toBe("aborted");
    });
  });

  describe("성공 응답을 받으면", () => {
    it("본문은 body에, x-request-id는 requestId에, Cache-Control과 Age는 lifetime에 옮겨서 리턴한다", async () => {
      respondWith(
        200,
        { routes: [] },
        { "cache-control": "public, max-age=60", age: "10", "x-request-id": "req-success" },
      );
      const result = await requestJson<{ routes: never[] }>("/api/v1/routes");
      expect(result.ok).toBe(true);
      if (!result.ok) return;
      expect(result.body).toEqual({ routes: [] });
      expect(result.requestId).toBe("req-success");
      expect(result.lifetime).toEqual({ noStore: false, maxAgeSeconds: 60, ageSeconds: 10 });
    });
  });
});
