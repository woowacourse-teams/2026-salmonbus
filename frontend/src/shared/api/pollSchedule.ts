import type { ApiFailure, ApiResult } from "./client";

const FALLBACK_DELAY_MS = 60_000;
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
