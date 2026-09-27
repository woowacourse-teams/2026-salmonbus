import type { ApproachingVehicle } from "@/shared/api/routeForecast.types";
import { toSeatEstimate, toSeatLevel, type SeatEstimate, type SeatLevel } from "./seatGrade";

export interface ForecastArrival {
  kind: "forecast";
  stopsAway: number;
  level: SeatLevel;
  seatEstimate: SeatEstimate;
}

export interface UnavailableArrival {
  kind: "unavailable";
  stopsAway: number;
  level?: never;
  seatEstimate?: never;
}

export type ArrivalView = ForecastArrival | UnavailableArrival;

const MAX_ARRIVALS = 3;

export function arrivalViewsFor(vehicles: readonly ApproachingVehicle[]): ArrivalView[] {
  return [...vehicles]
    .sort((left, right) => left.horizonStops - right.horizonStops)
    .slice(0, MAX_ARRIVALS)
    .map(toArrivalView);
}

export function representativeArrival(arrivals: readonly ArrivalView[]): ArrivalView | undefined {
  return arrivals[0];
}

function toArrivalView(vehicle: ApproachingVehicle): ArrivalView {
  const { horizonStops, forecast } = vehicle;
  if (forecast.status !== "AVAILABLE") {
    return {
      kind: "unavailable",
      stopsAway: horizonStops,
    };
  }

  return {
    kind: "forecast",
    stopsAway: horizonStops,
    level: toSeatLevel(forecast.seatAvailableProbability),
    seatEstimate: toSeatEstimate(forecast.expectedSeats),
  };
}
