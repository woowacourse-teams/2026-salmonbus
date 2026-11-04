import type { SeatEstimate } from "./seatGrade";

export function seatLabelOf(
  estimate: SeatEstimate,
  target?: "FIRST_POST_STOP_OBSERVATION" | "AFTER_ALIGHTING_ESTIMATE",
): string {
  if (target === "AFTER_ALIGHTING_ESTIMATE") {
    return estimate.kind === "unknown" ? "하차 후 예상 좌석 정보 없음" : `하차 후 ${estimate.seats}석 예상해요`;
  }
  if (estimate.kind === "unknown") return "예상 좌석 정보 없음";
  return estimate.seats === 0 ? "도착 시 빈자리가 없어요" : `도착 시 ${estimate.seats}석 예상해요`;
}
