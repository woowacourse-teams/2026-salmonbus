import { describe, expect, it } from "@jest/globals";
import type { ApiFailure, ApiResult } from "./client";
import { lastSuccessOf, nextResourceFrom, PENDING_RESOURCE, type PolledResource } from "./polledResource";

const lifetime = { noStore: false, maxAgeSeconds: 15, ageSeconds: 0 };
const networkFailure: ApiFailure = { kind: "network", cause: new Error("offline") };
const timeoutFailure: ApiFailure = { kind: "timeout" };

function succeeded(body: string): ApiResult<string> {
  return { ok: true, body, status: 200, requestId: null, clock: null, lifetime };
}

function failed(failure: ApiFailure): ApiResult<string> {
  return { ok: false, failure };
}

describe("nextResourceFrom", () => {
  it("처음 응답이 성공이면 본문과 수신 시각을 담은 성공이 된다", () => {
    expect(nextResourceFrom(PENDING_RESOURCE, succeeded("보드A"), 1_000)).toEqual({
      status: "success",
      lastSuccess: { body: "보드A", clock: null, receivedAt: 1_000 },
      lifetime,
    });
  });

  it("처음부터 실패하면 이전 본문이 없는 loadingError가 된다", () => {
    expect(nextResourceFrom(PENDING_RESOURCE, failed(networkFailure), 1_000)).toEqual({
      status: "loadingError",
      failure: networkFailure,
    });
  });

  it("성공한 뒤 갱신이 실패하면 이전 본문과 수신 시각을 유지한 refetchError가 된다", () => {
    const success = nextResourceFrom<string>(PENDING_RESOURCE, succeeded("보드A"), 1_000);

    expect(nextResourceFrom(success, failed(networkFailure), 2_000)).toEqual({
      status: "refetchError",
      failure: networkFailure,
      lastSuccess: { body: "보드A", clock: null, receivedAt: 1_000 },
    });
  });

  it("갱신 실패가 이어져도 처음 성공한 본문을 계속 유지한다", () => {
    const success = nextResourceFrom<string>(PENDING_RESOURCE, succeeded("보드A"), 1_000);
    const firstFailure = nextResourceFrom(success, failed(networkFailure), 2_000);

    expect(nextResourceFrom(firstFailure, failed(timeoutFailure), 3_000)).toEqual({
      status: "refetchError",
      failure: timeoutFailure,
      lastSuccess: { body: "보드A", clock: null, receivedAt: 1_000 },
    });
  });

  it("실패 뒤 성공하면 새 본문과 새 수신 시각으로 바뀐다", () => {
    const success = nextResourceFrom<string>(PENDING_RESOURCE, succeeded("보드A"), 1_000);
    const refetchError = nextResourceFrom(success, failed(networkFailure), 2_000);

    expect(nextResourceFrom(refetchError, succeeded("보드B"), 3_000)).toEqual({
      status: "success",
      lastSuccess: { body: "보드B", clock: null, receivedAt: 3_000 },
      lifetime,
    });
  });
});

describe("lastSuccessOf", () => {
  const lastSuccess = { body: "보드A", clock: null, receivedAt: 1_000 };

  it.each<[string, PolledResource<string>]>([
    ["pending", PENDING_RESOURCE],
    ["loadingError", { status: "loadingError", failure: networkFailure }],
  ])("%s 상태에서는 성공한 적이 없어 null을 돌려준다", (_status, resource) => {
    expect(lastSuccessOf(resource)).toBeNull();
  });

  it.each<[string, PolledResource<string>]>([
    ["success", { status: "success", lastSuccess, lifetime }],
    ["refetchError", { status: "refetchError", failure: networkFailure, lastSuccess }],
  ])("%s 상태에서는 마지막 성공을 돌려준다", (_status, resource) => {
    expect(lastSuccessOf(resource)).toEqual(lastSuccess);
  });
});
