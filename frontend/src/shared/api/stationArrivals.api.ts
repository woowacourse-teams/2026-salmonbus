import { requestJson, type ApiResult } from "./client";
import type { StationArrivalQuery, StationArrivalsResponse } from "./stationArrivals.types";

export function fetchStationArrivals(
  selection: StationArrivalQuery,
  options: { signal: AbortSignal },
): Promise<ApiResult<StationArrivalsResponse>> {
  const endpoint = "/api/v2/stations/:stationId/arrivals";
  const { stationId } = selection;
  let url = endpoint.replace(":stationId", encodeURIComponent(stationId));
  const tags: Record<string, string> = { station_id: stationId };
  if ("routeId" in selection) {
    const { routeId, referenceVersionId, stopSequence } = selection;
    const query = new URLSearchParams({ routeId, referenceVersionId, stopSequence: String(stopSequence) });
    url += `?${query}`;
    tags.route_id = routeId;
  }

  return requestJson<StationArrivalsResponse>(url, {
    signal: options.signal,
    errorContext: { endpoint, tags },
  });
}
