import type { ApiResult } from "@/shared/api/client";
import { nextPollFrom, type PollDecision } from "@/shared/api/pollSchedule";
import type { Direction } from "@/shared/api/routeForecast.types";
import type { RouteGeometry } from "@/shared/api/routeGeometry.types";
import type { GeoCoordinate, RouteMapData } from "../../verdict-board/map/routeMapModel";
import type { Point } from "./homeMapData";

/** 같은 노선의 정류장을 여러 개 저장했어도 노선은 한 번만 그리고, 먼저 저장한 방면을 쓴다. */
export function favoriteMapRoutesOf(
  favorites: readonly { routeId: string; direction: Direction }[],
): Map<string, Direction> {
  const directions = new Map<string, Direction>();
  for (const { routeId, direction } of favorites) {
    if (!directions.has(routeId)) directions.set(routeId, direction);
  }
  return directions;
}

/** 형상을 받았으면 다시 조회하지 않는다. 만드는 중이거나 수집 전이면 응답의 유효 시간 뒤에 다시 조회한다. */
export function nextGeometryPollFrom(result: ApiResult<RouteGeometry>): PollDecision {
  return result.ok && result.body.reason !== "GENERATION_PENDING" && result.body.reason !== "NOT_COLLECTED"
    ? { kind: "stop" }
    : nextPollFrom(result);
}

/** 지도를 화면에 맞출 때 쓰는 경로. 방향별 경로에 전체 경로를 더하고, 점이 하나뿐인 경로는 뺀다. */
export function overviewPathsOf(data: RouteMapData | null, fullPath: GeoCoordinate[] | undefined): Point[][] {
  if (data === null) return [];
  const coordinates = data.paths.map((path) => path.coordinates);
  if (fullPath) coordinates.push(fullPath);
  return coordinates
    .filter((path) => path.length > 1)
    .map((path) => path.map(({ longitude, latitude }): Point => [longitude, latitude]));
}
