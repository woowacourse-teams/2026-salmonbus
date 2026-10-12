import { requestJson, type ApiResult, type RequestOptions } from "./client";
import type { Board, LiveVehicles, RouteListResponse } from "./routeForecast.types";

const API_BASE = "/api/v1";

type FetchRequestOption = Required<Pick<RequestOptions, "signal">>;

const ENDPOINTS = {
  board: `${API_BASE}/routes/:routeId/board`,
  vehicles: `${API_BASE}/routes/:routeId/vehicles`,
};

function routeUrlFrom(endpoint: string, routeId: string): string {
  return endpoint.replace(":routeId", encodeURIComponent(routeId));
}

export function fetchRoutes(options: FetchRequestOption): Promise<ApiResult<RouteListResponse>> {
  return requestJson<RouteListResponse>(`${API_BASE}/routes`, options);
}

export function fetchBoard(routeId: string, options: FetchRequestOption): Promise<ApiResult<Board>> {
  const endpoint = ENDPOINTS.board;

  return requestJson<Board>(routeUrlFrom(endpoint, routeId), {
    ...options,
    errorContext: { endpoint, tags: { route_id: routeId } },
  });
}

export function fetchLiveVehicles(routeId: string, options: FetchRequestOption): Promise<ApiResult<LiveVehicles>> {
  const endpoint = ENDPOINTS.vehicles;

  return requestJson<LiveVehicles>(routeUrlFrom(endpoint, routeId), {
    ...options,
    errorContext: { endpoint, tags: { route_id: routeId } },
  });
}
