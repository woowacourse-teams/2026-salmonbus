export type SeatLevel = "high" | "low" | "veryLow";

export interface CountedSeats {
  kind: "count";
  seats: number;
}

export interface UnknownSeats {
  kind: "unknown";
  seats?: never;
}

export type SeatEstimate = CountedSeats | UnknownSeats;

const SEAT_LEVEL_THRESHOLDS = {
  high: 0.7,
  low: 0.3,
};

export function toSeatLevel(probability: number): SeatLevel {
  if (probability >= SEAT_LEVEL_THRESHOLDS.high) return "high";
  if (probability >= SEAT_LEVEL_THRESHOLDS.low) return "low";
  return "veryLow";
}

export function toSeatEstimate(expectedSeats: number | undefined): SeatEstimate {
  return expectedSeats === undefined ? { kind: "unknown" } : { kind: "count", seats: expectedSeats };
}
