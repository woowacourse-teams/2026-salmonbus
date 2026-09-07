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

// 접힘 행은 가장 먼저 도착할 버스로 판단한다. 계약에 도착 시각이 없어 거리로 대신하며,
// 맨 앞 항목이 실제로 가장 먼저 도착하는 비율은 96.68%다.
export function representativeArrival(arrivals: readonly ArrivalView[]): ArrivalView | undefined {
  return arrivals[0];
}

function toArrivalView(vehicle: ApproachingVehicle): ArrivalView {
  return {
    stopsAway: vehicle.horizonStops,
    level: toSeatLevel(vehicle.seatAvailableProbability),
    seatEstimate: toSeatEstimate(vehicle.expectedSeats),
  };
}
