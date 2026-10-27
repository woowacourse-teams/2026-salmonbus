import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import type { StationArrivalSelection, StationArrivalsResponse } from "@/shared/api/stationArrivals.types";
import type { ArrivalView } from "./arrivalPolicy";
import { toRemainingTime } from "@/shared/utils/remainingTime";
import { remainingTimeLabelOf } from "@/shared/utils/remainingTimeLabelOf";

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
  const now = clock.now();
  if (
    body.reason === "STALE" ||
    body.status !== "READY" ||
    body.snapshot === null ||
    !(now < Date.parse(body.snapshot.staleAt))
  ) {
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
  // 거리와 좌석은 watch를 유지하고, 수집 시점이 다른 ETA에서는 같은 차량의 시간만 보탠다.
  if (vehicle === undefined) return null;
  const { eta } = vehicle;
  if (eta.status !== "AVAILABLE" || eta.remainingSeconds === null) return null;
  const time = toRemainingTime((Date.parse(eta.expectedArrivalAt) - now) / 1000);
  return time.kind === "known" ? remainingTimeLabelOf(time) : null;
}
