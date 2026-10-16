import type { Direction } from "./routeForecast.types";

export interface StationArrivalSelection {
  stationId: string;
  routeId: string;
  referenceVersionId: string;
  stopSequence: number;
  direction: Direction;
}

export type StationArrivalStatus = "READY" | "NO_PREDICTION" | "UNAVAILABLE";

export interface ArrivalSnapshot {
  snapshotId: string;
  receivedAt: string;
  sourceObservedAt: string | null;
  sourceQueryTime: string | null;
  refreshAfterAt: string;
  staleAt: string;
}

export interface ArrivalRefresh {
  status: "IDLE" | "RUNNING" | "BLOCKED" | "FAILED";
  reason: string | null;
  suggestedRefreshAt: string;
}

export interface ArrivalTargetVisit {
  referenceVersionId: string | null;
  stopSequence: number | null;
  sourceStopOrder: number;
  matchStatus: "MATCHED" | "UNVERIFIED";
  direction: Direction | null;
}

export interface ArrivalEta {
  status: "AVAILABLE" | "UNAVAILABLE";
  reason: string | null;
  resolution: "SECONDS" | "MINUTES";
  estimateBaseAt: string;
  baseKind: "RECEIVED_AT_APPROXIMATION";
  expectedArrivalAt: string;
  remainingSeconds: number | null;
}

export interface ArrivalSeat {
  kind: "EXACT" | "UNKNOWN";
  remaining: number | null;
  reason: string | null;
}

export interface ArrivalVehicle {
  vehicleId: string | null;
  ordinal: 1 | 2;
  remainingStops: number | null;
  eta: ArrivalEta;
  currentSeat: ArrivalSeat;
  congestionCode: number | null;
  vehicleTypeCode: number | null;
  taglessSupported: boolean | null;
}

export interface ArrivalRoute {
  routeId: string;
  name: string | null;
  targetVisit: ArrivalTargetVisit;
  status: StationArrivalStatus;
  reason: string | null;
  arrivals: ArrivalVehicle[];
}

export interface StationArrivalsResponse {
  stationId: string;
  generatedAt: string;
  status: StationArrivalStatus;
  reason: string | null;
  snapshot: ArrivalSnapshot | null;
  refresh: ArrivalRefresh;
  routes: ArrivalRoute[];
}
