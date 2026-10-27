import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import type { StationArrivalSelection, StationArrivalsResponse } from "@/shared/api/stationArrivals.types";
import type { ArrivalView } from "./arrivalPolicy";

export interface StationArrivalDetails {
  selection: StationArrivalSelection;
  resource: PolledResource<StationArrivalsResponse>;
}

export function arrivalTimingFor(arrival: ArrivalView, details: StationArrivalDetails | null): string | null {
  if (arrival.stopsAway === 0 || details === null) return null;
  const { resource, selection } = details;

  const latest = latestSuccessOf(resource);
  if (latest === null || latest.clock === null) return null;
  const { body, clock } = latest;
  if (body.stationId !== selection.stationId) return null;
  if (body.status !== "READY" || body.snapshot === null || !(clock.now() < Date.parse(body.snapshot.staleAt))) {
    return null;
  }

  const route = body.routes.find(
    ({ routeId, targetVisit }) =>
      routeId === selection.routeId &&
      targetVisit.matchStatus === "MATCHED" &&
      targetVisit.referenceVersionId === selection.referenceVersionId &&
      targetVisit.stopSequence === selection.stopSequence &&
      targetVisit.direction === selection.direction,
  );
  if (route?.status !== "READY" || arrival.vehicleId === null) return null;
  const matches = route.arrivals.filter(({ vehicleId }) => vehicleId === arrival.vehicleId);
  const vehicle = matches.length === 1 ? matches[0] : undefined;
  if (vehicle === undefined || vehicle.remainingStops !== arrival.stopsAway) return null;
  const { eta } = vehicle;
  const seconds = (Date.parse(eta.expectedArrivalAt) - clock.now()) / 1000;
  if (eta.status !== "AVAILABLE" || eta.remainingSeconds === null || !(seconds > 0)) return null;

  return seconds <= 60 ? "1분 이내" : `${Math.ceil(seconds / 60)}분 남음`;
}
