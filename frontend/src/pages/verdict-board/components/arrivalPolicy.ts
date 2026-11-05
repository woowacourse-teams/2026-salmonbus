import type { ApproachingVehicle } from "@/shared/api/routeForecast.types";
import { toSeatEstimate, toSeatLevel, type SeatEstimate, type SeatLevel } from "@/shared/utils/seatGrade";

interface ArrivalBase {
  vehicleId: string | null;
  stopsAway: number;
}

export interface AvailableArrival extends ArrivalBase {
  kind: "available";
  level: SeatLevel;
  seatEstimate: SeatEstimate;
}

export interface UnavailableArrival extends ArrivalBase {
  kind: "unavailable";
  level?: never;
  seatEstimate?: never;
}

export type ArrivalView = AvailableArrival | UnavailableArrival;
export type ArrivalTone = SeatLevel | "unavailable";

const MAX_ARRIVALS = 3;

export function arrivalViewsFor(vehicles: readonly ApproachingVehicle[]): ArrivalView[] {
  return vehicles.slice(0, MAX_ARRIVALS).map(toArrivalView);
}

export function representativeArrival(arrivals: readonly [ArrivalView, ...ArrivalView[]]): ArrivalView {
  return arrivals[0];
}

export function arrivalToneFor(arrival: ArrivalView): ArrivalTone {
  return arrival.kind === "available" ? arrival.level : "unavailable";
}

function toArrivalView(vehicle: ApproachingVehicle): ArrivalView {
  const { vehicleId, remainingStops, forecast } = vehicle;
  if (forecast.status !== "AVAILABLE") {
    return {
      kind: "unavailable",
      vehicleId,
      stopsAway: remainingStops,
    };
  }

  return {
    kind: "available",
    vehicleId,
    stopsAway: remainingStops,
    level: toSeatLevel(forecast.seatAvailableProbability),
    seatEstimate: toSeatEstimate(forecast.expectedSeats),
  };
}
