import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import type { StationArrivalSelection, StationArrivalsResponse } from "@/shared/api/stationArrivals.types";
import type { ArrivalView } from "./arrivalPolicy";

export interface StationArrivalDetails {
  selection: StationArrivalSelection;
  resource: PolledResource<StationArrivalsResponse>;
}

export interface ArrivalTiming {
  available: boolean;
  label: string;
}

// 정류장에 도착한 차량은 시간 칸을 비우도록 null을 돌려준다.
export function arrivalTimingFor(arrival: ArrivalView, details: StationArrivalDetails | null): ArrivalTiming | null {
  if (arrival.stopsAway === 0) return null;
  const unavailable = { available: false, label: "시간 정보 없음" };
  if (details === null) return unavailable;
  const { resource, selection } = details;
  if (resource.status === "pending") return { ...unavailable, label: "확인 중" };

  const latest = latestSuccessOf(resource);
  if (latest === null || latest.clock === null) return unavailable;
  const { body, clock } = latest;
  if (body.stationId !== selection.stationId) return unavailable;
  if (body.status === "UNAVAILABLE" && body.reason === "NOT_COLLECTED" && body.refresh.status === "RUNNING") {
    return { ...unavailable, label: "확인 중" };
  }
  if (body.status !== "READY" || body.snapshot === null || !(clock.now() < Date.parse(body.snapshot.staleAt))) {
    return unavailable;
  }

  const route = body.routes.find(
    ({ routeId, targetVisit }) =>
      routeId === selection.routeId &&
      targetVisit.matchStatus === "MATCHED" &&
      targetVisit.referenceVersionId === selection.referenceVersionId &&
      targetVisit.stopSequence === selection.stopSequence &&
      targetVisit.direction === selection.direction,
  );
  if (route?.status !== "READY" || arrival.vehicleId === null) return unavailable;
  const matches = route.arrivals.filter(({ vehicleId }) => vehicleId === arrival.vehicleId);
  const vehicle = matches.length === 1 ? matches[0] : undefined;
  if (vehicle === undefined || vehicle.remainingStops !== arrival.stopsAway) return unavailable;
  const { eta } = vehicle;
  const seconds = (Date.parse(eta.expectedArrivalAt) - clock.now()) / 1000;
  if (eta.status !== "AVAILABLE" || eta.remainingSeconds === null || !(seconds > 0)) return unavailable;

  return {
    available: true,
    label: seconds <= 60 ? "1분 이내" : `${Math.ceil(seconds / 60)}분 남음`,
  };
}
