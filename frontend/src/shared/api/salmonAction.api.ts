import { requestJson, type ApiResult } from "./client";

export interface SalmonActionSelection {
  routeId: string;
  stationId: string;
  stopSequence: number;
}

export interface SalmonActionPayload {
  kind: "DISABLED" | "DECIDED";
  reasonCode?: string;
  guidance?: string;
}

export function fetchSalmonAction(
  selection: SalmonActionSelection,
  signal: AbortSignal,
): Promise<ApiResult<SalmonActionPayload>> {
  const endpoint = "/api/v1/routes/:routeId/stations/:stationId/salmon-action";
  const url =
    `/api/v1/routes/${encodeURIComponent(selection.routeId)}` +
    `/stations/${encodeURIComponent(selection.stationId)}/salmon-action` +
    `?stopSequence=${encodeURIComponent(String(selection.stopSequence))}`;

  return requestJson<SalmonActionPayload>(url, {
    signal,
    errorContext: {
      endpoint,
      tags: { route_id: selection.routeId, station_id: selection.stationId },
    },
  });
}
