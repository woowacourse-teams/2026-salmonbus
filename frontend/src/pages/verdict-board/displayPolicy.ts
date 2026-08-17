import type { Board, Direction, StopState } from "./types";

export type SeatLevel = "high" | "low" | "veryLow";

export interface BoardingStopView {
  kind: "boarding";
  sequence: number;
  name: string;
  level: SeatLevel;
}

export interface PassThroughStopView {
  kind: "passThrough";
  sequence: number;
  name: string;
}

export type StopView = BoardingStopView | PassThroughStopView;

const SEAT_LEVEL_THRESHOLDS = {
  high: 0.7,
  low: 0.3,
};

export function toSeatLevel(probability: number): SeatLevel {
  if (probability >= SEAT_LEVEL_THRESHOLDS.high) return "high";
  if (probability >= SEAT_LEVEL_THRESHOLDS.low) return "low";
  return "veryLow";
}

export function toStopView(stop: StopState): StopView {
  if (stop.seatForecast.kind === "NOT_APPLICABLE") {
    return { kind: "passThrough", sequence: stop.sequence, name: stop.name };
  }
  return {
    kind: "boarding",
    sequence: stop.sequence,
    name: stop.name,
    level: toSeatLevel(stop.seatForecast.seatAvailableProbability),
  };
}

export function stopViewsFor(board: Board, direction: Direction): StopView[] {
  return board.stops
    .filter((stop) => stop.direction === direction)
    .toSorted((left, right) => left.sequence - right.sequence)
    .map(toStopView);
}

export const SEAT_LEVEL_LABELS: Record<SeatLevel, string> = {
  high: "탑승 가능성 높음",
  low: "탑승 가능성 낮음",
  veryLow: "탑승 가능성 매우 낮음",
};
