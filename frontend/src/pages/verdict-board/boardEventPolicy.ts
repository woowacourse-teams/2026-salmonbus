import type { EventMap } from "@/shared/analytics/events";
import type { Board, Direction } from "@/shared/api/routeForecast.types";
import { stopViewsFor } from "./displayPolicy";
import type { SeatLevel } from "./seatGrade";

interface BoardViewTiming {
  now: number;
  loadMs: number;
}

export function boardViewedPropertiesOf(
  board: Board,
  direction: Direction,
  { now, loadMs }: BoardViewTiming,
): EventMap["board_viewed"] {
  const stops = stopViewsFor(board, direction);
  const forecastStops = stops.filter((stop) => stop.kind === "boarding");
  const levelCounts: Record<SeatLevel, number> = { high: 0, low: 0, veryLow: 0 };
  for (const stop of forecastStops) {
    levelCounts[stop.level] += 1;
  }

  return {
    route_id: board.route.id,
    direction,
    model_release_id: board.model.releaseId,
    forecast_age_sec: Math.round((now - Date.parse(board.observedAt)) / 1000),
    vehicles_in_service: board.vehiclesInService,
    boarding_stop_count: stops.filter((stop) => stop.kind !== "passThrough").length,
    forecast_stop_count: forecastStops.length,
    high_count: levelCounts.high,
    low_count: levelCounts.low,
    very_low_count: levelCounts.veryLow,
    load_ms: Math.round(loadMs),
  };
}
