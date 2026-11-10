import type { RemainingTime } from "./remainingTime";

export function remainingTimeLabelOf(time: RemainingTime, withinMinuteLabel = "1분 이내"): string {
  if (time.kind === "unknown") return "시간 정보 없음";
  return time.seconds <= 60 ? withinMinuteLabel : `${Math.ceil(time.seconds / 60)}분 남음`;
}
