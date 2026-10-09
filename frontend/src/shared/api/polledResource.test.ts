import { describe, expect, it } from "@jest/globals";
import type { ApiFailure, ApiResult } from "./client";
import { latestSuccessOf, nextResourceFrom, PENDING_RESOURCE, type PolledResource } from "./polledResource";
import type { ReferenceClock } from "./referenceClock";

const lifetime = { noStore: false, maxAgeSeconds: 15, ageSeconds: 0 };
const networkFailure: ApiFailure = { kind: "network", cause: new Error("offline") };
const timeoutFailure: ApiFailure = { kind: "timeout" };
const clockA: ReferenceClock = { servedAt: 1_000, now: () => 1_000 };
const clockB: ReferenceClock = { servedAt: 3_000, now: () => 3_000 };

function succeeded(body: string, clock: ReferenceClock): ApiResult<string> {
  return { ok: true, body, status: 200, requestId: null, clock, lifetime };
}

function failed(failure: ApiFailure): ApiResult<string> {
  return { ok: false, failure };
}

describe("nextResourceFrom", () => {
  it("처음 응답이 성공이면 본문, 서버 시계, 수신 시각을 담은 성공이 된다", () => {
    expect(nextResourceFrom(PENDING_RESOURCE, succeeded("보드A", clockA), 1_000)).toEqual({
      status: "success",
      latestSuccess: { body: "보드A", clock: clockA, receivedAt: 1_000 },
      lifetime,
    });
  });

  it("처음부터 실패하면 이전 본문이 없는 loadingError가 된다", () => {
    expect(nextResourceFrom(PENDING_RESOURCE, failed(networkFailure), 1_000)).toEqual({
      status: "loadingError",
      failure: networkFailure,
    });
  });

  it("성공한 뒤 갱신이 실패하면 이전 본문, 서버 시계, 수신 시각을 유지한 refetchError가 된다", () => {
    const success = nextResourceFrom<string>(PENDING_RESOURCE, succeeded("보드A", clockA), 1_000);

    expect(nextResourceFrom(success, failed(networkFailure), 2_000)).toEqual({
      status: "refetchError",
      failure: networkFailure,
      latestSuccess: { body: "보드A", clock: clockA, receivedAt: 1_000 },
    });
  });

  it("갱신 실패가 이어져도 처음 성공한 본문을 계속 유지한다", () => {
    const success = nextResourceFrom<string>(PENDING_RESOURCE, succeeded("보드A", clockA), 1_000);
    const firstFailure = nextResourceFrom(success, failed(networkFailure), 2_000);

    expect(nextResourceFrom(firstFailure, failed(timeoutFailure), 3_000)).toEqual({
      status: "refetchError",
      failure: timeoutFailure,
      latestSuccess: { body: "보드A", clock: clockA, receivedAt: 1_000 },
    });
  });

  it("실패 뒤 성공하면 새 본문, 새 서버 시계, 새 수신 시각으로 바뀐다", () => {
    const success = nextResourceFrom<string>(PENDING_RESOURCE, succeeded("보드A", clockA), 1_000);
    const refetchError = nextResourceFrom(success, failed(networkFailure), 2_000);

    expect(nextResourceFrom(refetchError, succeeded("보드B", clockB), 3_000)).toEqual({
      status: "success",
      latestSuccess: { body: "보드B", clock: clockB, receivedAt: 3_000 },
      lifetime,
    });
  });
});

describe("latestSuccessOf", () => {
  const latestSuccess = { body: "보드A", clock: null, receivedAt: 1_000 };

  it.each<[string, PolledResource<string>]>([
    ["pending", PENDING_RESOURCE],
    ["loadingError", { status: "loadingError", failure: networkFailure }],
  ])("%s 상태에서는 성공한 적이 없어 null을 돌려준다", (_status, resource) => {
    expect(latestSuccessOf(resource)).toBeNull();
  });

  it.each<[string, PolledResource<string>]>([
    ["success", { status: "success", latestSuccess, lifetime }],
    ["refetchError", { status: "refetchError", failure: networkFailure, latestSuccess }],
  ])("%s 상태에서는 가장 최근 성공을 돌려준다", (_status, resource) => {
    expect(latestSuccessOf(resource)).toEqual(latestSuccess);
  });
});
