import type { ApiResult } from "./client";
import { nextPollFrom, type PollDecision } from "./pollSchedule";
import { nextResourceFrom, type PolledResource } from "./polledResource";
import type { StationArrivalSelection, StationArrivalsResponse } from "./stationArrivals.types";

const MIN_REFRESH_DELAY_MS = 1_000;
const FALLBACK_REFRESH_DELAY_MS = 60_000;

export function stationArrivalSelectionKey(selection: StationArrivalSelection): string {
  return JSON.stringify([
    selection.stationId,
    selection.routeId,
    selection.referenceVersionId,
    selection.stopSequence,
    selection.direction,
  ]);
}

/** 수집 중 no-store 응답은 초 단위 Date 대신 응답 생성 시각부터 단조 시계로 진행한다. */
export function withStationArrivalClock(
  result: ApiResult<StationArrivalsResponse>,
  elapsedNow: () => number = () => performance.now(),
): ApiResult<StationArrivalsResponse> {
  if (!result.ok) return result;
  const shouldUseGeneratedAt = result.lifetime.noStore && result.body.refresh.reason === "COLLECTING";
  if (result.clock !== null && !shouldUseGeneratedAt) return result;

  const generatedAt = Date.parse(result.body.generatedAt);
  if (!Number.isFinite(generatedAt)) return result;

  const servedAt = generatedAt + result.lifetime.ageSeconds * 1_000;
  const receivedAt = elapsedNow();
  return { ...result, clock: { servedAt, now: () => servedAt + elapsedNow() - receivedAt } };
}

/** HTTP 캐시 최대 5초와 무관하게 서버가 알려준 다음 수집 시각을 따른다. */
export function nextStationArrivalPollFrom(result: ApiResult<StationArrivalsResponse>): PollDecision {
  if (!result.ok) return nextPollFrom(result);

  const suggestedAt = Date.parse(result.body.refresh.suggestedRefreshAt);
  const referenceNow = result.clock?.now() ?? Date.parse(result.body.generatedAt) + result.lifetime.ageSeconds * 1_000;
  if (!Number.isFinite(suggestedAt) || !Number.isFinite(referenceNow)) {
    return { kind: "again", delayMs: FALLBACK_REFRESH_DELAY_MS };
  }

  return { kind: "again", delayMs: Math.max(MIN_REFRESH_DELAY_MS, suggestedAt - referenceNow) };
}

export function nextStationArrivalsResource(
  previous: PolledResource<StationArrivalsResponse>,
  result: ApiResult<StationArrivalsResponse>,
  receivedAt: number,
): PolledResource<StationArrivalsResponse> {
  // 판본 충돌 뒤에는 이전 방문의 ETA를 보존하지 않는다. 새 방문을 선택해야 다시 조회한다.
  if (!result.ok && result.failure.kind === "contract" && result.failure.status === 409) {
    return { status: "loadingError", failure: result.failure };
  }

  return nextResourceFrom(previous, result, receivedAt);
}
