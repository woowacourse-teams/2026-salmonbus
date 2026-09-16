import { afterEach, describe, expect, it, jest } from "@jest/globals";
import { requestJson, type ApiFailure, type ApiResult } from "./client";

const originalFetch = global.fetch;

afterEach(() => {
  global.fetch = originalFetch;
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
  describe("오류 응답을 받으면", () => {
    it("계약에 있는 code일 경우 retryable 필드가 있어도 contract로 분류한다", async () => {
      respondWith(503, { code: "MODEL_OUT_OF_SCOPE", message: "지원하지 않는 판본", requestId: "req-contract" });
      const failure = failureOf(await requestJson("/api/v1/routes/R1/board"));
      expect(failure).toMatchObject({ kind: "contract", status: 503, error: { code: "MODEL_OUT_OF_SCOPE" } });
    });

    it("계약에 없는 code일 경우 malformed로 분류한다", async () => {
      respondWith(500, { code: "SOMETHING_NEW", message: "x", requestId: "req-unknown-code" });
      const failure = failureOf(await requestJson("/api/v1/routes/R1/board"));
      expect(failure).toMatchObject({ kind: "malformed", status: 500 });
    });

    it("본문이 JSON이 아니라면 malformed로 분류한다", async () => {
      respondText(200, "<html>gateway</html>");
      const failure = failureOf(await requestJson("/api/v1/routes"));
      expect(failure).toMatchObject({ kind: "malformed", status: 200 });
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
