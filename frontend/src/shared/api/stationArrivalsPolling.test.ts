import { describe, expect, it } from "@jest/globals";
import type { ApiFailure, ApiResult, ApiSuccess } from "./client";
import { PENDING_RESOURCE } from "./polledResource";
import {
  nextStationArrivalPollFrom,
  nextStationArrivalsResource,
  stationArrivalSelectionKey,
  withStationArrivalClock,
} from "./stationArrivalsPolling";
import type { StationArrivalSelection, StationArrivalsResponse } from "./stationArrivals.types";

const generatedAt = "2026-10-14T12:00:10+09:00";

function success(overrides: Partial<ApiSuccess<StationArrivalsResponse>> = {}): ApiSuccess<StationArrivalsResponse> {
  return {
    ok: true,
    status: 200,
    requestId: null,
    clock: null,
    lifetime: { maxAgeSeconds: 5, ageSeconds: 0, noStore: false },
    body: {
      stationId: "206000637",
      generatedAt,
      status: "UNAVAILABLE",
      reason: "NOT_COLLECTED",
      snapshot: null,
      refresh: { status: "RUNNING", reason: "COLLECTING", suggestedRefreshAt: "2026-10-14T12:00:12+09:00" },
      routes: [],
    },
    ...overrides,
  };
}

function failed(failure: ApiFailure): ApiResult<StationArrivalsResponse> {
  return { ok: false, failure };
}

describe("stationArrivalSelectionKey", () => {
  it("같은 정류장의 노선·판본·반복 방문·방향을 모두 구분한다", () => {
    const selection: StationArrivalSelection = {
      stationId: "206000637",
      routeId: "204000057",
      referenceVersionId: "9223372036854775807",
      stopSequence: 22,
      direction: "UP",
    };
    const variants: StationArrivalSelection[] = [
      selection,
      { ...selection, stationId: "206000638" },
      { ...selection, routeId: "204000058" },
      { ...selection, referenceVersionId: "9223372036854775806" },
      { ...selection, stopSequence: 27 },
      { ...selection, direction: "DOWN" },
    ];

    expect(new Set(variants.map(stationArrivalSelectionKey)).size).toBe(variants.length);
    expect(stationArrivalSelectionKey({ ...selection })).toBe(stationArrivalSelectionKey(selection));
  });
});

describe("withStationArrivalClock", () => {
  it("Date/Age로 만들어진 서버 시계가 있으면 응답 생성 시각으로 덮어쓰지 않는다", () => {
    const clock = { servedAt: 1_000, now: () => 2_000 };
    const result = success({ clock });

    expect(withStationArrivalClock(result)).toBe(result);
  });

  it("Date가 없으면 생성 시각과 Age에서 시작해 경과 시간만 더한다", () => {
    let elapsed = 500;
    const result = withStationArrivalClock(
      success({ lifetime: { maxAgeSeconds: 5, ageSeconds: 3, noStore: false } }),
      () => elapsed,
    );
    if (!result.ok || result.clock === null) throw new Error("서버 기준 시계가 필요합니다");

    expect(result.clock.servedAt).toBe(Date.parse(generatedAt) + 3_000);
    elapsed += 2_500;
    expect(result.clock.now()).toBe(Date.parse(generatedAt) + 5_500);
  });
});

describe("nextStationArrivalPollFrom", () => {
  it.each([false, true])("HTTP 캐시나 no-store(%s)와 무관하게 수집 중 2초 재조회를 따른다", (noStore) => {
    const result = success({ lifetime: { maxAgeSeconds: 5, ageSeconds: 0, noStore } });

    expect(nextStationArrivalPollFrom(result)).toEqual({ kind: "again", delayMs: 2_000 });
  });

  it("Date/Age 서버 시각과 경과 시간으로 다음 권장 갱신까지 남은 시간을 계산한다", () => {
    const result = success({ clock: { servedAt: Date.parse(generatedAt), now: () => Date.parse(generatedAt) + 500 } });

    expect(nextStationArrivalPollFrom(result)).toEqual({ kind: "again", delayMs: 1_500 });
  });

  it("권장 시각이 지났거나 1초보다 가까우면 즉시 반복하지 않고 1초 뒤 조회한다", () => {
    const result = success({
      clock: { servedAt: Date.parse(generatedAt), now: () => Date.parse(generatedAt) + 3_000 },
    });

    expect(nextStationArrivalPollFrom(result)).toEqual({ kind: "again", delayMs: 1_000 });
  });

  it("사용할 수 없는 권장 시각은 HTTP 캐시를 폴링 주기로 삼지 않고 기본 간격으로 복구한다", () => {
    const result = success();
    result.body.refresh.suggestedRefreshAt = "invalid";

    expect(nextStationArrivalPollFrom(result)).toEqual({ kind: "again", delayMs: 60_000 });
  });

  it("HTTP 503은 기존 Retry-After 정책을 사용하고 판본 충돌은 재조회를 멈춘다", () => {
    expect(
      nextStationArrivalPollFrom(
        failed({
          kind: "contract",
          status: 503,
          retryAfterMs: 30_000,
          error: { code: "SERVICE_UNAVAILABLE", message: "", requestId: "arrival-503" },
        }),
      ),
    ).toEqual({ kind: "again", delayMs: 30_000 });
    expect(
      nextStationArrivalPollFrom(
        failed({
          kind: "contract",
          status: 409,
          retryAfterMs: null,
          error: { code: "REFERENCE_VERSION_CONFLICT", message: "", requestId: "arrival-409" },
        }),
      ),
    ).toEqual({ kind: "stop" });
  });
});

describe("nextStationArrivalsResource", () => {
  it("네트워크 갱신 실패에는 마지막 데이터를 보존하지만 판본 충돌에는 이전 ETA를 버린다", () => {
    const previous = nextStationArrivalsResource(PENDING_RESOURCE, success(), 10);
    const failedRefresh = nextStationArrivalsResource(previous, failed({ kind: "network", cause: null }), 20);
    expect(failedRefresh).toMatchObject({ status: "refetchError", latestSuccess: { receivedAt: 10 } });

    const conflict: ApiFailure = {
      kind: "contract",
      status: 409,
      retryAfterMs: null,
      error: { code: "REFERENCE_VERSION_CONFLICT", message: "", requestId: "arrival-409" },
    };
    const conflicted = nextStationArrivalsResource(failedRefresh, failed(conflict), 30);

    expect(conflicted).toEqual({ status: "loadingError", failure: conflict });
    expect(conflicted).not.toHaveProperty("latestSuccess");
  });

  it("fallback 시계를 성공 resource에 보존해 UI 카운트다운에도 같은 시계를 제공한다", () => {
    const result = withStationArrivalClock(success(), () => 500);
    const resource = nextStationArrivalsResource(PENDING_RESOURCE, result, 999_999);

    expect(resource).toMatchObject({
      status: "success",
      latestSuccess: { clock: { servedAt: Date.parse(generatedAt) } },
    });
  });
});
