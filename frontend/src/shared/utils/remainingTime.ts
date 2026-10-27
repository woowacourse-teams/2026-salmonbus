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
