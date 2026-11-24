export interface KnownRemainingTime {
  kind: "known";
  seconds: number;
}

export interface UnknownRemainingTime {
  kind: "unknown";
  seconds?: never;
}

export type RemainingTime = KnownRemainingTime | UnknownRemainingTime;

export function toRemainingTime(seconds: number | null): RemainingTime {
  return seconds !== null && seconds > 0 ? { kind: "known", seconds } : { kind: "unknown" };
}

/** 응답을 받은 뒤 흐른 시간만큼 줄인 남은 시간이다. 남은 초를 모르면 흐른 시간과 상관없이 시간 없음이다. */
export function remainingTimeFrom(remainingSeconds: number | null, elapsedSeconds: number): RemainingTime {
  return toRemainingTime(remainingSeconds === null ? null : remainingSeconds - elapsedSeconds);
}
