import type { ApiFailure, ApiResult } from "./client";

// 오류 응답은 no-store라 서버가 다음 시각을 알려주지 않는다. 그때만 이 값을 쓴다.
const FALLBACK_DELAY_MS = 60_000;
// 서버가 max-age 0을 주더라도 연속 호출로 흐르지 않게 막는다.
const MIN_DELAY_MS = 5_000;

export type PollDecision = { kind: "again"; delayMs: number } | { kind: "stop" };

export function nextPollFrom(result: ApiResult<unknown>): PollDecision {
  if (!result.ok) {
    return afterFailure(result.failure);
  }

  const { noStore, maxAgeSeconds, ageSeconds } = result.lifetime;
  if (noStore || maxAgeSeconds === null) {
    return again(FALLBACK_DELAY_MS);
  }

  return again(Math.max(0, maxAgeSeconds - ageSeconds) * 1000);
}

function afterFailure(failure: ApiFailure): PollDecision {
  switch (failure.kind) {
    case "aborted":
      return { kind: "stop" };
    // 4xx는 다시 불러도 같은 답이 온다. 없는 노선이나 잘못된 주소다.
    case "contract":
      return failure.status >= 500 ? again(failure.retryAfterMs ?? FALLBACK_DELAY_MS) : { kind: "stop" };
    case "rateLimited":
      return again(failure.retryAfterMs ?? FALLBACK_DELAY_MS);
    case "timeout":
    case "network":
    case "malformed":
      return again(FALLBACK_DELAY_MS);
  }
}

function again(delayMs: number): PollDecision {
  return { kind: "again", delayMs: Math.max(MIN_DELAY_MS, delayMs) };
}
