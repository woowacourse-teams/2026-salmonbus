import { requestJson, type ApiResult } from "./client";
import type { RouteGeometry } from "./routeGeometry.types";

/** 노선 선택 시 조회한다. 차량·ETA 폴링과 별도로 브라우저 HTTP 캐시를 이용한다. */
export function fetchRouteGeometry(
  routeId: string,
  options: { signal: AbortSignal; referenceVersionId?: string },
): Promise<ApiResult<RouteGeometry>> {
  const endpoint = "/api/v2/routes/:routeId/geometry";
  const query =
    options.referenceVersionId === undefined
      ? ""
      : `?referenceVersionId=${encodeURIComponent(options.referenceVersionId)}`;
  return requestJson<RouteGeometry>(endpoint.replace(":routeId", encodeURIComponent(routeId)) + query, {
    signal: options.signal,
    errorContext: { endpoint, tags: { route_id: routeId } },
  });
}
