import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import type { StationArrivalSelection, StationArrivalsResponse } from "@/shared/api/stationArrivals.types";
import { toRemainingTime } from "@/shared/utils/remainingTime";
import { remainingTimeLabelOf } from "@/shared/utils/remainingTimeLabelOf";
import type { ArrivalView } from "./arrivalPolicy";
import { ARRIVAL_TIME_LABELS } from "./verdictCopy";

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
  const unavailable = { available: false, label: remainingTimeLabelOf({ kind: "unknown" }) };
  if (details === null) return unavailable;
  const { resource, selection } = details;
  if (resource.status === "pending" || resource.status === "idle") {
    return { ...unavailable, label: ARRIVAL_TIME_LABELS.loading };
  }

  const latest = latestSuccessOf(resource);
  if (latest === null || latest.clock === null) return unavailable;
  const { body, clock } = latest;
  if (body.stationId !== selection.stationId) return unavailable;
  if (body.status === "UNAVAILABLE" && body.reason === "NOT_COLLECTED" && body.refresh.status === "RUNNING") {
    return { ...unavailable, label: ARRIVAL_TIME_LABELS.loading };
  }
  const now = clock.now();
  if (body.reason === "STALE" || (body.snapshot !== null && now >= Date.parse(body.snapshot.staleAt))) {
    return { ...unavailable, label: ARRIVAL_TIME_LABELS.stale };
  }
  if (body.status !== "READY" || body.snapshot === null || !(now < Date.parse(body.snapshot.staleAt))) {
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
  // 거리와 좌석은 watch를 유지하고, 수집 시점이 다른 ETA에서는 같은 차량의 시간만 보탠다.
  if (vehicle === undefined) return unavailable;
  const { eta } = vehicle;
  if (eta.status !== "AVAILABLE" || eta.remainingSeconds === null) return unavailable;
  const time = toRemainingTime((Date.parse(eta.expectedArrivalAt) - now) / 1000);
  return { available: time.kind === "known", label: remainingTimeLabelOf(time) };
}
