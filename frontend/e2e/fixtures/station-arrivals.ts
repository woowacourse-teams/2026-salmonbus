import type { StationArrivalsResponse } from "../../src/shared/api/stationArrivals.types";

export function createStationArrivalsData(url: URL, now: number): StationArrivalsResponse {
  const stopSequence = Number(url.searchParams.get("stopSequence"));
  return {
    stationId: url.pathname.split("/").at(-2)!,
    generatedAt: new Date(now).toISOString(),
    status: "NO_PREDICTION",
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
        status: "NO_PREDICTION",
        reason: null,
        arrivals: [],
      },
    ],
  };
}
