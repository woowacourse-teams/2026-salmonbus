import { isRecord } from "@/shared/utils/valueGuards";
import type { Direction } from "@/shared/api/routeForecast.types";

export type Point = readonly [longitude: number, latitude: number];
export const DEFAULT_LOCATION: Point = [127.111136, 37.394761];

export interface HomeStopRoute {
  routeId: string;
  routeName: string;
  direction: Direction;
}

export interface HomeMapData {
  stops: HomeStop[];
  routePaths: Point[][];
}

export interface HomeStop {
  id: string;
  name: string;
  point: Point;
  routes: HomeStopRoute[];
  source?: "station-api";
}

function pointFrom(value: unknown): value is [number, number] {
  return (
    Array.isArray(value) &&
    value.length === 2 &&
    value.every((part) => typeof part === "number" && Number.isFinite(part)) &&
    Math.abs(value[0]) <= 180 &&
    Math.abs(value[1]) <= 90
  );
}

function isHomeStopRoute(value: unknown): value is HomeStopRoute & Record<string, unknown> {
  return (
    isRecord(value) &&
    typeof value.routeId === "string" &&
    /^\d{9}$/.test(value.routeId) &&
    typeof value.routeName === "string" &&
    value.routeName.length > 0 &&
    (value.direction === "UP" || value.direction === "DOWN")
  );
}

export function homeMapDataFrom(value: unknown): HomeMapData {
  if (!isRecord(value)) throw new Error("지도 데이터 응답 형식 오류");
  const stops: HomeStop[] = [];
  if (Array.isArray(value.stops)) {
    for (const stop of value.stops) {
      if (
        !isRecord(stop) ||
        typeof stop.id !== "string" ||
        !stop.id ||
        typeof stop.name !== "string" ||
        !stop.name ||
        !pointFrom(stop.point)
      )
        continue;
      const routes = Array.isArray(stop.routes) ? stop.routes.filter(isHomeStopRoute) : [];
      stops.push({ id: stop.id, name: stop.name, point: stop.point, routes });
    }
  }
  const routePaths = Array.isArray(value.routePaths)
    ? value.routePaths.filter(
        (path): path is Point[] => Array.isArray(path) && path.length >= 2 && path.every(pointFrom),
      )
    : [];
  return { stops, routePaths };
}

export function distanceMeters(a: Point, b: Point): number {
  const radians = Math.PI / 180;
  return Math.hypot((a[0] - b[0]) * Math.cos(((a[1] + b[1]) / 2) * radians), a[1] - b[1]) * 111_195;
}
