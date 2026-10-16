import { requestJson, type ApiResult } from "./client";
import type { StationArrivalSelection, StationArrivalsResponse } from "./stationArrivals.types";

export function fetchStationArrivals(
  selection: StationArrivalSelection,
  options: { signal: AbortSignal },
): Promise<ApiResult<StationArrivalsResponse>> {
  const endpoint = "/api/v2/stations/:stationId/arrivals";
  const { stationId, routeId, referenceVersionId, stopSequence } = selection;
  const query = new URLSearchParams({ routeId, referenceVersionId, stopSequence: String(stopSequence) });
  const url = `${endpoint.replace(":stationId", encodeURIComponent(stationId))}?${query}`;

  return requestJson<StationArrivalsResponse>(url, {
    signal: options.signal,
    errorContext: { endpoint, tags: { route_id: routeId, station_id: stationId } },
  });
}
