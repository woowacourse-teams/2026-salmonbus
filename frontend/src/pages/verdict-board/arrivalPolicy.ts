import type { ApproachingVehicle } from "@/shared/api/routeForecast.types";
import { toSeatEstimate, toSeatLevel, type SeatEstimate, type SeatLevel } from "./seatGrade";

export interface ArrivalView {
  stopsAway: number;
  level: SeatLevel;
  seatEstimate: SeatEstimate;
}

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
  if (forecast.status === "UNAVAILABLE") {
    return {
      stopsAway: horizonStops,
      level: "unknown",
      seatEstimate: { kind: "unknown" },
    };
  }

  return {
    stopsAway: horizonStops,
    level: toSeatLevel(forecast.seatAvailableProbability),
    seatEstimate: toSeatEstimate(forecast.expectedSeats),
  };
}
