import type { BoardRoute } from "@/shared/api/routeForecast.types";
import type { RouteGeometry, RouteMapPoint } from "@/shared/api/routeGeometry.types";
import type { GeoCoordinate, RouteMapData, RouteMapStop } from "./routeMapModel";

export function routeGeometryMapDataFor(
  geometry: RouteGeometry,
  route: Pick<BoardRoute, "id" | "referenceVersionId">,
): RouteMapData | null {
  if (
    geometry.routeId !== route.id ||
    geometry.referenceVersionId !== route.referenceVersionId ||
    geometry.coordinateSystem !== "WGS84_ASSUMED" ||
    (geometry.status !== "READY" && geometry.status !== "PARTIAL" && geometry.status !== "UNAVAILABLE")
  ) {
    return null;
  }

  const fullPath = validPath(geometry.points) ? geometry.points.map(coordinateFor) : [];
  const paths: RouteMapData["paths"] = [];
  for (const direction of ["UP", "DOWN"] as const) {
    const points = geometry.paths?.[direction].points;
    // 중간의 잘못된 점만 빼면 서로 떨어진 도로를 직선으로 이을 수 있으므로 경로 전체를 제외한다.
    if (points === undefined || !validPath(points)) continue;
    paths.push({ direction, coordinates: points.map(coordinateFor) });
  }
  const stops: RouteMapStop[] = geometry.busStops.flatMap((stop) => {
    if (stop.point === null || !validPoint(stop.point)) return [];
    return [
      {
        sequence: stop.stopSequence,
        stopId: stop.stationId,
        direction: stop.direction,
        coordinate: coordinateFor(stop.point),
      },
    ];
  });
  if (stops.length === 0 && paths.length === 0 && fullPath.length === 0) return null;

  return { routeId: geometry.routeId, referenceVersionId: geometry.referenceVersionId, stops, paths, fullPath };
}

function validPoint(point: RouteMapPoint): boolean {
  return (
    Number.isFinite(point.x) &&
    point.x >= -180 &&
    point.x <= 180 &&
    Number.isFinite(point.y) &&
    point.y >= -90 &&
    point.y <= 90
  );
}

function validPath(points: RouteMapPoint[]): boolean {
  return points.length >= 2 && points.every(validPoint) && hasDistinctPoints(points);
}

function hasDistinctPoints(points: RouteMapPoint[]): boolean {
  const first = points[0];
  return first !== undefined && points.some((point) => point.x !== first.x || point.y !== first.y);
}

function coordinateFor(point: RouteMapPoint): GeoCoordinate {
  return { latitude: point.y, longitude: point.x };
}
