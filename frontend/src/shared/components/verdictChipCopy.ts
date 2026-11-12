import { seatLevelLabels, type SeatLevel } from "@/shared/utils/seatGrade";

export type ChipTone = SeatLevel | "unavailable" | "noVehicle" | "checking";

const chipLabels: Record<ChipTone, string> = {
  high: `탑승 확률 ${seatLevelLabels.high}`,
  low: `탑승 확률 ${seatLevelLabels.low}`,
  veryLow: `탑승 확률 ${seatLevelLabels.veryLow}`,
  unavailable: "좌석 예측 정보가 없어요",
  noVehicle: "지금 오는 차량이 없어요",
  checking: "정보가 오래됐어요",
};

export function chipLabel(tone: ChipTone): string {
  return chipLabels[tone];
}
