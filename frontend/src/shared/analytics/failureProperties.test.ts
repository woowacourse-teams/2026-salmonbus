import { describe, expect, it } from "@jest/globals";
import type { ApiFailure } from "@/shared/api/client";
import { failurePropertiesOf } from "./failureProperties";

function contractFailure(code: "MODEL_OUT_OF_SCOPE" | "INVALID_ROUTE_ID", status: number): ApiFailure {
  return {
    kind: "contract",
    status,
    retryAfterMs: null,
    error: { code, message: "실패", requestId: "req-1" },
  };
}

describe("failurePropertiesOf", () => {
  it("모델이 따로 다루지 않는 노선일 경우 에러 코드를 함께 남긴다", () => {
    expect(failurePropertiesOf(contractFailure("MODEL_OUT_OF_SCOPE", 503))).toStrictEqual({
      error_kind: "contract",
      http_status: 503,
      error_code: "MODEL_OUT_OF_SCOPE",
    });
  });

  it("서버가 보낸 다른 에러 코드는 남기지 않고 상태 코드만 남긴다", () => {
    expect(failurePropertiesOf(contractFailure("INVALID_ROUTE_ID", 400))).toStrictEqual({
      error_kind: "contract",
      http_status: 400,
    });
  });

  it("서버 응답을 해석할 수 없다면 상태 코드를 남긴다", () => {
    expect(failurePropertiesOf({ kind: "malformed", status: 500, requestId: null })).toStrictEqual({
      error_kind: "malformed",
      http_status: 500,
    });
  });

  it("요청이 너무 많아 Too many request로 막히면 429를 남긴다", () => {
    expect(failurePropertiesOf({ kind: "rateLimited", retryAfterMs: 1000, requestId: null })).toStrictEqual({
      error_kind: "rateLimited",
      http_status: 429,
    });
  });

  it("응답이 없는 실패는 종류만 남긴다", () => {
    expect(failurePropertiesOf({ kind: "timeout" })).toStrictEqual({ error_kind: "timeout" });
    expect(failurePropertiesOf({ kind: "network", cause: new TypeError("Failed to fetch") })).toStrictEqual({
      error_kind: "network",
    });
  });

  it("사용자가 떠나서 취소된 요청은 실패로 세지 않는다", () => {
    expect(failurePropertiesOf({ kind: "aborted" })).toBeNull();
  });
});
