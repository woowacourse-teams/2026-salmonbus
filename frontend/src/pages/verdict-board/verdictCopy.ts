import type { StopView } from "./displayPolicy";
import type { SeatEstimate, SeatLevel } from "./seatGrade";

export type ChipTone = SeatLevel | "noForecast";

const CHIP_LABELS: Record<ChipTone, string> = {
  high: "탑승 확률 높음",
  low: "탑승 확률 낮음",
  veryLow: "탑승 확률 매우 낮음",
  noForecast: "지금 오는 차량이 없어요",
};

const SEAT_UNKNOWN_LABEL = "좌석을 예측하기 어려워요";
const SEAT_EMPTY_LABEL = "도착 시 빈자리가 없어요";

const WAYPOINT_SUFFIX = "(경유)";
const TURNAROUND_SUFFIX = "(회차지점)";

export const WAYPOINT_CAPTION = "경유";
export const NO_FORECAST_NOTICE = "현재 예측할 수 있는 차량이 없습니다";
export const OUT_OF_SERVICE_NOTICE = "현재는 운행시간이 아닙니다";
export const LOADING_NOTICE = "불러오는 중이에요";
export const LOAD_FAILED_NOTICE = "정보를 불러오지 못했어요";

export function chipLabel(tone: ChipTone): string {
  return CHIP_LABELS[tone];
}

export function stopNameLabel(stop: StopView): string {
  const name = stop.kind === "passThrough" ? withoutWaypointSuffix(stop.name) : stop.name;
  return stop.isTurnaround ? `${name} ${TURNAROUND_SUFFIX}` : name;
}

// 경유 지점은 이름 끝에 (경유)가 붙어 오는데 행에 같은 뜻의 캡션이 이미 있다.
function withoutWaypointSuffix(name: string): string {
  return name.endsWith(WAYPOINT_SUFFIX) ? name.slice(0, -WAYPOINT_SUFFIX.length).trimEnd() : name;
}

export function stopsAwayLabel(stopsAway: number): string {
  return `${stopsAway}정류장 전`;
}

export function seatLabel(estimate: SeatEstimate): string {
  if (estimate.kind === "unknown") return SEAT_UNKNOWN_LABEL;
  if (estimate.seats === 0) return SEAT_EMPTY_LABEL;
  return `도착 시 ${Math.floor(estimate.seats)}석 예상해요`;
}
