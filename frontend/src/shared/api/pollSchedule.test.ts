import { describe, expect, it } from "@jest/globals";
import type { ApiFailure, ApiResult } from "./client";
import { nextPollFrom } from "./pollSchedule";
import type { ErrorCode } from "./routeForecast.types";

function success(lifetime: {
  maxAgeSeconds: number | null;
  ageSeconds?: number;
  noStore?: boolean;
}): ApiResult<unknown> {
  return {
    ok: true,
    body: {},
    status: 200,
    requestId: null,
    clock: null,
    lifetime: { noStore: false, ageSeconds: 0, ...lifetime },
  };
}

function failed(failure: ApiFailure): ApiResult<unknown> {
  return { ok: false, failure };
}

function contract(status: number, code: ErrorCode, retryAfterMs: number | null = null): ApiFailure {
  return {
    kind: "contract",
    status,
    retryAfterMs,
    error: { code, message: "", requestId: "req-contract" },
  };
}

describe("nextPollFrom", () => {
  describe("성공 응답을 받으면", () => {
    it("max-age에서 age를 뺀 초만큼 기다린 뒤 다시 부른다", () => {
      expect(nextPollFrom(success({ maxAgeSeconds: 60, ageSeconds: 10 }))).toEqual({ kind: "again", delayMs: 50_000 });
    });

    it("남은 시간이 5초보다 짧아도 5초는 기다린다", () => {
      expect(nextPollFrom(success({ maxAgeSeconds: 1 }))).toEqual({ kind: "again", delayMs: 5_000 });
    });

    it("no-store면 기본값 60초를 기다린다", () => {
      expect(nextPollFrom(success({ maxAgeSeconds: 15, noStore: true }))).toEqual({ kind: "again", delayMs: 60_000 });
    });

    it("max-age가 없으면 기본값 60초를 기다린다", () => {
      expect(nextPollFrom(success({ maxAgeSeconds: null }))).toEqual({ kind: "again", delayMs: 60_000 });
    });
  });

  describe("실패 응답을 받으면", () => {
    // prettier-ignore
    it.each([
      ["취소되면 멈춘다", failed({ kind: "aborted" }), { kind: "stop" }],
      ["4xx 오류라면 어차피 다시 불러도 같은 상황이 반복되므로 멈춘다", failed(contract(404, "ROUTE_NOT_FOUND")), { kind: "stop" }],
      ["5xx 오류에 Retry-After가 있으면 그만큼 기다린다", failed(contract(503, "SERVICE_UNAVAILABLE", 30_000)), { kind: "again", delayMs: 30_000 }],
      ["5xx 오류에 Retry-After가 없다면 60초를 기다린다", failed(contract(503, "SERVICE_UNAVAILABLE")), { kind: "again", delayMs: 60_000 }],
      ["시간 초과면 60초를 기다린다", failed({ kind: "timeout" }), { kind: "again", delayMs: 60_000 }],
      ["네트워크 오류면 60초를 기다린다", failed({ kind: "network", cause: null }), { kind: "again", delayMs: 60_000 }],
      ["형식 오류면 60초를 기다린다", failed({ kind: "malformed", status: 500, requestId: null }), { kind: "again", delayMs: 60_000 }],
    ])("%s", (_, result, expected) => {
      expect(nextPollFrom(result)).toEqual(expected);
    });
  });
});
