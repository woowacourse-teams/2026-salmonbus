import type { Board, Direction, StopState } from "@/shared/api/routeForecast.types";

export interface StopSelection {
  direction: Direction;
  sequence: StopState["sequence"];
}

export function stopSelectionFrom(board: Board, direction: Direction, stopId: string | null): StopSelection | null {
  const matches = board.stops.filter(
    (stop) => stop.stopId === stopId && stop.direction === direction && stop.boardingAllowed,
  );
  const stop = matches.length === 1 ? matches[0] : undefined;
  return stop === undefined ? null : { direction, sequence: stop.sequence };
}
