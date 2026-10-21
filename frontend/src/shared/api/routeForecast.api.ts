import { requestJson, type ApiResult, type RequestOptions } from "./client";
import type { RouteListResponse, RouteWatch } from "./routeForecast.types";

const API_BASE = "/api/v1";

type FetchRequestOption = Required<Pick<RequestOptions, "signal">>;

const ENDPOINTS = {
  watch: `${API_BASE}/routes/:routeId/watch`,
};

function routeUrlFrom(endpoint: string, routeId: string): string {
  return endpoint.replace(":routeId", encodeURIComponent(routeId));
}

export function fetchRoutes(options: FetchRequestOption): Promise<ApiResult<RouteListResponse>> {
  return requestJson<RouteListResponse>(`${API_BASE}/routes`, options);
}

export function fetchRouteWatch(routeId: string, options: FetchRequestOption): Promise<ApiResult<RouteWatch>> {
  const endpoint = ENDPOINTS.watch;

  return requestJson<RouteWatch>(routeUrlFrom(endpoint, routeId), {
    ...options,
    errorContext: { endpoint, tags: { route_id: routeId } },
  });
}
