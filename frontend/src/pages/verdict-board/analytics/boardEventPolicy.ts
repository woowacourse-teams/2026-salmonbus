import type { EventMap } from "@/shared/analytics/events";
import type { Board, Direction } from "@/shared/api/routeForecast.types";
import type { ArrivalTone } from "../arrivalPolicy";
import { stopViewsFor } from "../displayPolicy";

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
  const toneCounts: Record<ArrivalTone, number> = { high: 0, low: 0, veryLow: 0, unavailable: 0 };
  for (const stop of stops) {
    if (stop.kind === "boarding") {
      toneCounts[stop.tone] += 1;
    }
  }

  return {
    route_id: board.route.id,
    direction,
    model_release_id: board.model.releaseId,
    forecast_age_sec: Math.round((now - Date.parse(board.observedAt)) / 1000),
    vehicles_in_service: board.vehiclesInService,
    boarding_stop_count: stops.filter((stop) => stop.kind !== "passThrough").length,
    forecast_stop_count: toneCounts.high + toneCounts.low + toneCounts.veryLow,
    high_count: toneCounts.high,
    low_count: toneCounts.low,
    very_low_count: toneCounts.veryLow,
    unavailable_count: toneCounts.unavailable,
    load_ms: Math.round(loadMs),
  };
}
