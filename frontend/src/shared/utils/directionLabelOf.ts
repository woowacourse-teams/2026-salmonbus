import type { Direction } from "@/shared/api/routeForecast.types";

export function directionLabelOf(terminalStopName: string | undefined, direction: Direction | null): string {
  const terminal = terminalStopName?.trim();
  if (terminal) return `${terminal} 방면`;
  if (direction === "UP") return "상행";
  if (direction === "DOWN") return "하행";
  return "방면 정보 없음";
}
