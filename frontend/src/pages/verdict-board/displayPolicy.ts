import type { Board, Direction, StopState } from "@/shared/api/routeForecast.types";

export type SeatLevel = "high" | "low" | "veryLow";
export type WaitingReason = "serviceEnded" | "routeHead" | "noVehicle";

interface StopViewBase {
  sequence: number;
  stopId: string;
  name: string;
}

export interface BoardingStopView extends StopViewBase {
  kind: "boarding";
  level: SeatLevel;
}

export interface PassThroughStopView extends StopViewBase {
  kind: "passThrough";
}

export interface WaitingStopView extends StopViewBase {
  kind: "waiting";
  reason: WaitingReason;
}

export type StopView = BoardingStopView | PassThroughStopView | WaitingStopView;

export interface DirectionView {
  id: Direction;
  label: string;
}

const SEAT_LEVEL_THRESHOLDS = {
  high: 0.7,
  low: 0.3,
};

const ROUTE_HEAD_MAX_SEQUENCE = 12;

export function toSeatLevel(probability: number): SeatLevel {
  if (probability >= SEAT_LEVEL_THRESHOLDS.high) return "high";
  if (probability >= SEAT_LEVEL_THRESHOLDS.low) return "low";
  return "veryLow";
}

export function toStopView(stop: StopState, vehiclesInService: number): StopView {
  const { sequence, stopId, name } = stop;

  if (!stop.boardingAllowed) {
    return { kind: "passThrough", sequence, stopId, name };
  }

  const nearest = stop.approachingVehicles[0];
  if (nearest !== undefined) {
    return { kind: "boarding", sequence, stopId, name, level: toSeatLevel(nearest.seatAvailableProbability) };
  }

  return { kind: "waiting", sequence, stopId, name, reason: waitingReasonFor(sequence, vehiclesInService) };
}

function waitingReasonFor(sequence: number, vehiclesInService: number): WaitingReason {
  if (vehiclesInService === 0) return "serviceEnded";
  if (sequence <= ROUTE_HEAD_MAX_SEQUENCE) return "routeHead";
  return "noVehicle";
}

export function stopViewsFor(board: Board, direction: Direction): StopView[] {
  return board.stops
    .filter((stop) => stop.direction === direction)
    .sort((left, right) => left.sequence - right.sequence)
    .map((stop) => toStopView(stop, board.vehiclesInService));
}

export function directionViewsFor(board: Board): DirectionView[] {
  return board.route.directions.map((info) => ({
    id: info.id,
    label: `${info.originStopName} → ${info.terminalStopName}`,
  }));
}

export const SEAT_LEVEL_LABELS: Record<SeatLevel, string> = {
  high: "탑승 가능성 높음",
  low: "탑승 가능성 낮음",
  veryLow: "탑승 가능성 매우 낮음",
};

export const WAITING_LABELS: Record<WaitingReason, string> = {
  serviceEnded: "운행 종료",
  routeHead: "출발 구간",
  noVehicle: "곧 오는 버스 없음",
};
