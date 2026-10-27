import type { ApproachingVehicle } from "../../src/shared/api/routeForecast.types";
import type { StationArrivalsResponse } from "../../src/shared/api/stationArrivals.types";

export function createStationArrivalsData(
  url: URL,
  now: number,
  vehicles: readonly ApproachingVehicle[] = [],
): StationArrivalsResponse {
  // HTTP Date와 같은 초 단위 기준으로 ETA를 만든다.
  now = Math.floor(now / 1000) * 1000;
  const stopSequence = Number(url.searchParams.get("stopSequence"));
  return {
    stationId: url.pathname.split("/").at(-2)!,
    generatedAt: new Date(now).toISOString(),
    status: vehicles.length === 0 ? "NO_PREDICTION" : "READY",
    reason: null,
    snapshot: {
      snapshotId: "00000000-0000-0000-0000-000000000001",
      receivedAt: new Date(now).toISOString(),
      sourceObservedAt: null,
      sourceQueryTime: null,
      refreshAfterAt: new Date(now + 40_000).toISOString(),
      staleAt: new Date(now + 60_000).toISOString(),
    },
    refresh: { status: "IDLE", reason: null, suggestedRefreshAt: new Date(now + 40_000).toISOString() },
    routes: [
      {
        routeId: url.searchParams.get("routeId")!,
        name: "3330",
        targetVisit: {
          referenceVersionId: url.searchParams.get("referenceVersionId"),
          stopSequence,
          sourceStopOrder: stopSequence,
          matchStatus: "MATCHED",
          direction: stopSequence <= 12 ? "UP" : "DOWN",
        },
        status: vehicles.length === 0 ? "NO_PREDICTION" : "READY",
        reason: null,
        arrivals: vehicles.slice(0, 2).map((vehicle, index) => ({
          vehicleId: vehicle.vehicleId,
          ordinal: index === 0 ? 1 : 2,
          remainingStops: vehicle.remainingStops,
          eta: {
            status: "AVAILABLE",
            reason: null,
            resolution: "SECONDS",
            estimateBaseAt: new Date(now).toISOString(),
            baseKind: "RECEIVED_AT_APPROXIMATION",
            expectedArrivalAt: new Date(now + (index + 1) * 120_000).toISOString(),
            remainingSeconds: (index + 1) * 120,
          },
          currentSeat: { kind: "UNKNOWN", remaining: null, reason: "NOT_REPORTED" },
          congestionCode: null,
          vehicleTypeCode: null,
          taglessSupported: null,
        })),
      },
    ],
  };
}
