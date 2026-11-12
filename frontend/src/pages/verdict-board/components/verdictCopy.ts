import type { ArrivalView } from "./arrivalPolicy";
import type { BoardNotice, StopView } from "./displayPolicy";
import { seatLabelOf } from "@/shared/utils/seatLabelOf";

export const NO_INCOMING_VEHICLE_NOTICE = "지금 오는 차량이 없어요";
export const STALE_FORECAST_NOTICE = "새 정보가 오면 좌석 예측을 다시 보여드려요";
export const REFRESH_FAILED_NOTICE = "새 정보를 불러오지 못했어요";

const WAYPOINT_SUFFIX = "(경유)";
const TURNAROUND_SUFFIX = "(회차지점)";

export const WAYPOINT_CAPTION = "경유";
export const NO_VEHICLE_NOTICE = "현재 예측할 수 있는 차량이 없습니다";
export const OUT_OF_SERVICE_NOTICE = "현재는 운행시간이 아닙니다";
export const LOADING_NOTICE = "불러오는 중이에요";
export const LOAD_FAILED_NOTICE = "노선 정보를 불러오지 못했어요";

export function boardNoticeLabel(notice: BoardNotice): string {
  switch (notice.kind) {
    case "noVehiclesInService":
      return NO_INCOMING_VEHICLE_NOTICE;
    case "expired":
    case "refreshFailed":
      return notice.observedClock === null
        ? REFRESH_FAILED_NOTICE
        : `${REFRESH_FAILED_NOTICE} · ${notice.observedClock} 기준`;
  }
}

export function stopNameLabel(stop: StopView): string {
  const name = stop.kind === "passThrough" ? withoutWaypointSuffix(stop.name) : stop.name;
  return stop.isTurnaround ? `${name} ${TURNAROUND_SUFFIX}` : name;
}

// 경유 지점은 이름 끝에 (경유)가 붙어 오는데 행에 같은 뜻의 캡션이 이미 있다.
function withoutWaypointSuffix(name: string): string {
  return name.endsWith(WAYPOINT_SUFFIX) ? name.slice(0, -WAYPOINT_SUFFIX.length).trimEnd() : name;
}

export function arrivalSeatLabel(arrival: ArrivalView): string {
  return seatLabelOf(arrival.kind === "available" ? arrival.seatEstimate : { kind: "unknown" });
}
