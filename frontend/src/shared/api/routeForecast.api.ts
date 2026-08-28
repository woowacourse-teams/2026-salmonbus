import { requestJson, type ApiResult, type RequestOptions } from "./client";
import type { Board, LiveVehicles, RouteListResponse } from "./routeForecast.types";

const API_BASE = "/api/v1";

export function fetchRoutes(options?: RequestOptions): Promise<ApiResult<RouteListResponse>> {
  return requestJson<RouteListResponse>(`${API_BASE}/routes`, options);
}

export function fetchBoard(routeId: string, options?: RequestOptions): Promise<ApiResult<Board>> {
  return requestJson<Board>(`${API_BASE}/routes/${encodeURIComponent(routeId)}/board`, options);
}

export function fetchLiveVehicles(routeId: string, options?: RequestOptions): Promise<ApiResult<LiveVehicles>> {
  return requestJson<LiveVehicles>(`${API_BASE}/routes/${encodeURIComponent(routeId)}/vehicles`, options);
}
