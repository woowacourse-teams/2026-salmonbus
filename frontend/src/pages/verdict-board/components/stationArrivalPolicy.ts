import { latestSuccessOf, type PolledResource } from "@/shared/api/polledResource";
import type {
  ArrivalVehicle,
  StationArrivalSelection,
  StationArrivalsResponse,
} from "@/shared/api/stationArrivals.types";
import { arrivalToneFor, type ArrivalTone, type ArrivalView } from "./arrivalPolicy";
import { arrivalSeatLabel, SEAT_UNKNOWN_LABEL } from "./verdictCopy";

export interface StationArrivalDetails {
  selection: StationArrivalSelection;
  resource: PolledResource<StationArrivalsResponse>;
}

interface ArrivalTiming {
  available: boolean;
  label: string;
}

interface StationArrivalView {
  ordinal: number;
  remainingStops: number | null;
  timing: ArrivalTiming | null;
  tone: ArrivalTone;
  seatLabel: string;
}

export type StationArrivalPanel =
  { kind: "loading" | "unavailable" | "stale" | "empty" } | { kind: "ready"; arrivals: StationArrivalView[] };

export function stationArrivalPanelFor(
  details: StationArrivalDetails | null,
  forecasts: readonly ArrivalView[],
): StationArrivalPanel {
  if (details === null) return { kind: "loading" };
  const { resource, selection } = details;
  if (resource.status === "pending" || resource.status === "idle") return { kind: "loading" };

  const latest = latestSuccessOf(resource);
  if (latest === null || latest.clock === null) return { kind: "unavailable" };
  const { body, clock } = latest;
  if (body.stationId !== selection.stationId) return { kind: "unavailable" };
  if (body.status === "UNAVAILABLE" && body.reason === "NOT_COLLECTED" && body.refresh.status === "RUNNING") {
    return { kind: "loading" };
  }
  const now = clock.now();
  if (body.reason === "STALE" || (body.snapshot !== null && now >= Date.parse(body.snapshot.staleAt))) {
    return { kind: "stale" };
  }
  if (body.status === "UNAVAILABLE" || body.snapshot === null || !(now < Date.parse(body.snapshot.staleAt))) {
    return { kind: "unavailable" };
  }
  if (body.status === "NO_PREDICTION" && body.routes.length === 0) return { kind: "empty" };

  const route = body.routes.find(
    ({ routeId, targetVisit }) =>
      routeId === selection.routeId &&
      targetVisit.matchStatus === "MATCHED" &&
      targetVisit.referenceVersionId === selection.referenceVersionId &&
      targetVisit.stopSequence === selection.stopSequence &&
      targetVisit.direction === selection.direction,
  );
  if (route === undefined || route.status === "UNAVAILABLE") return { kind: "unavailable" };
  if (route.status === "NO_PREDICTION" || route.arrivals.length === 0) return { kind: "empty" };

  return {
    kind: "ready",
    arrivals: route.arrivals.map((vehicle) => {
      const matches = forecasts.filter(({ vehicleId }) => vehicleId !== null && vehicleId === vehicle.vehicleId);
      // 중복 차량 ID는 어느 예보인지 확정할 수 없으므로 좌석을 연결하지 않는다.
      const uniqueVehicle = route.arrivals.filter(({ vehicleId }) => vehicleId === vehicle.vehicleId).length === 1;
      const forecast = uniqueVehicle && matches.length === 1 ? matches[0] : undefined;
      return {
        ordinal: vehicle.ordinal,
        remainingStops: vehicle.remainingStops,
        timing: arrivalTimingFor(vehicle, now),
        tone: forecast === undefined ? "unavailable" : arrivalToneFor(forecast),
        seatLabel: forecast === undefined ? SEAT_UNKNOWN_LABEL : arrivalSeatLabel(forecast),
      };
    }),
  };
}

function arrivalTimingFor(vehicle: ArrivalVehicle, now: number): ArrivalTiming | null {
  if (vehicle.remainingStops === 0) return null;
  const { eta } = vehicle;
  const seconds = (Date.parse(eta.expectedArrivalAt) - now) / 1000;
  if (eta.status !== "AVAILABLE" || eta.remainingSeconds === null || !(seconds > 0)) {
    return { available: false, label: "시간 정보 없음" };
  }
  return {
    available: true,
    label: seconds <= 60 ? "1분 이내" : `${Math.ceil(seconds / 60)}분 남음`,
  };
}
