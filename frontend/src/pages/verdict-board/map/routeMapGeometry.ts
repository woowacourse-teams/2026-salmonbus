import type { Direction } from "@/shared/api/routeForecast.types";
import type { GeoCoordinate, RouteMapData, RouteMapStop } from "./routeMapModel";

export interface ScreenPoint {
  x: number;
  y: number;
}

export interface RouteArrow {
  coordinate: GeoCoordinate;
  rotation: number;
}

interface PreparedRoutePath {
  coordinates: readonly GeoCoordinate[];
  distances: number[];
  stopSequences: RouteMapStop["sequence"][];
  stopDistances: number[];
}

// 경로와 정류장 투영은 데이터가 바뀔 때만 계산한다. 차량 프레임에서는 두 구간만 찾는다.
const pathCache = new WeakMap<RouteMapData, Map<Direction, PreparedRoutePath>>();

export function routeMapCoordinateForSequence(
  data: RouteMapData,
  sequencePosition: number,
  direction: Direction,
): GeoCoordinate | null {
  if (!Number.isFinite(sequencePosition)) return null;
  // 방향별 도로 경로가 없으면 보고된 정류장 좌표만 표시하고 정류장 사이를 직선으로 보간하지 않는다.
  if (!data.paths.some((path) => path.direction === direction)) {
    return (
      data.stops.find((stop) => stop.direction === direction && stop.sequence === sequencePosition)?.coordinate ?? null
    );
  }
  let byDirection = pathCache.get(data);
  if (byDirection === undefined) {
    byDirection = new Map();
    pathCache.set(data, byDirection);
  }
  let prepared = byDirection.get(direction);
  if (prepared === undefined) {
    prepared = preparedRoutePathFor(data, direction);
    byDirection.set(direction, prepared);
  }
  const afterIndex = upperBoundIndexFor(prepared.stopSequences, sequencePosition);
  const beforeIndex = Math.max(0, afterIndex - 1);
  const beforeSequence = prepared.stopSequences[beforeIndex];
  const afterSequence = prepared.stopSequences[afterIndex];
  let distance = prepared.stopDistances[beforeIndex];
  if (beforeSequence === undefined || distance === undefined) return null;
  const afterDistance = prepared.stopDistances[afterIndex];
  if (afterSequence !== undefined && afterDistance !== undefined && beforeSequence !== afterSequence) {
    const fraction = Math.max(0, (sequencePosition - beforeSequence) / (afterSequence - beforeSequence));
    distance += (afterDistance - distance) * fraction;
  }
  const endIndex = upperBoundIndexFor(prepared.distances, distance);
  const fromIndex = Math.max(0, endIndex - 1);
  const from = prepared.coordinates[fromIndex];
  const to = prepared.coordinates[endIndex];
  const startDistance = prepared.distances[fromIndex];
  const endDistance = prepared.distances[endIndex];
  if (from === undefined || startDistance === undefined) return null;
  if (to === undefined || endDistance === undefined || endDistance === startDistance) return from;
  return interpolatedCoordinateFor(from, to, (distance - startDistance) / (endDistance - startDistance));
}

function preparedRoutePathFor(data: RouteMapData, direction: Direction): PreparedRoutePath {
  const stops = data.stops.filter((stop) => stop.direction === direction).sort((a, b) => a.sequence - b.sequence);
  const coordinates = data.paths.find((path) => path.direction === direction)?.coordinates ?? [];
  const longitudeScale = Math.cos(((coordinates[0]?.latitude ?? 0) * Math.PI) / 180);
  const distances = coordinates.length === 0 ? [] : [0];
  for (let index = 1; index < coordinates.length; index += 1) {
    const from = coordinates[index - 1]!;
    const to = coordinates[index]!;
    distances.push(
      distances[index - 1]! + Math.hypot(to.latitude - from.latitude, (to.longitude - from.longitude) * longitudeScale),
    );
  }
  let minimumDistance = 0;
  const stopDistances = stops.map((stop) => {
    let nearestDistance = minimumDistance;
    let nearestSquaredError = Infinity;
    for (let index = 1; index < coordinates.length; index += 1) {
      const from = coordinates[index - 1]!;
      const to = coordinates[index]!;
      const startDistance = distances[index - 1]!;
      const endDistance = distances[index]!;
      if (endDistance < minimumDistance || endDistance === startDistance) continue;
      const dx = (to.longitude - from.longitude) * longitudeScale;
      const dy = to.latitude - from.latitude;
      const px = (stop.coordinate.longitude - from.longitude) * longitudeScale;
      const py = stop.coordinate.latitude - from.latitude;
      const fraction = Math.min(
        1,
        Math.max(
          0,
          (minimumDistance - startDistance) / (endDistance - startDistance),
          (px * dx + py * dy) / (dx * dx + dy * dy),
        ),
      );
      const squaredError = (px - dx * fraction) ** 2 + (py - dy * fraction) ** 2;
      if (squaredError < nearestSquaredError) {
        nearestSquaredError = squaredError;
        nearestDistance = startDistance + (endDistance - startDistance) * fraction;
      }
    }
    minimumDistance = nearestDistance;
    return nearestDistance;
  });
  return { coordinates, distances, stopSequences: stops.map((stop) => stop.sequence), stopDistances };
}

function upperBoundIndexFor(values: readonly number[], target: number): number {
  let left = 0;
  let right = values.length;
  while (left < right) {
    const middle = Math.floor((left + right) / 2);
    if (values[middle]! <= target) left = middle + 1;
    else right = middle;
  }
  return left;
}

function interpolatedCoordinateFor(from: GeoCoordinate, to: GeoCoordinate, fraction: number): GeoCoordinate {
  return {
    latitude: from.latitude + (to.latitude - from.latitude) * fraction,
    longitude: from.longitude + (to.longitude - from.longitude) * fraction,
  };
}

// 화면 픽셀 간격으로 샘플링하여 줌아웃에서도 방향이 읽히게 한다.
export function routeArrowsFor(
  coordinates: readonly GeoCoordinate[],
  project: (coordinate: GeoCoordinate) => ScreenPoint,
  viewport: { width: number; height: number },
): RouteArrow[] {
  const spacing = 100;
  const arrows: RouteArrow[] = [];
  const segments: {
    from: GeoCoordinate;
    to: GeoCoordinate;
    start: ScreenPoint;
    dx: number;
    dy: number;
    length: number;
  }[] = [];
  let nextOffset = spacing / 2;
  let totalLength = 0;
  for (let index = 1; index < coordinates.length; index += 1) {
    const from = coordinates[index - 1];
    const to = coordinates[index];
    if (from === undefined || to === undefined) continue;
    const start = project(from);
    const end = project(to);
    const dx = end.x - start.x;
    const dy = end.y - start.y;
    const length = Math.hypot(dx, dy);
    if (!Number.isFinite(length) || length === 0) continue;
    segments.push({ from, to, start, dx, dy, length });
    totalLength += length;
    for (; nextOffset < length; nextOffset += spacing) {
      const fraction = nextOffset / length;
      const x = start.x + dx * fraction;
      const y = start.y + dy * fraction;
      if (x < -16 || x > viewport.width + 16 || y < -16 || y > viewport.height + 16) continue;
      arrows.push({
        coordinate: interpolatedCoordinateFor(from, to, fraction),
        rotation: Math.atan2(dy, dx) * (180 / Math.PI),
      });
    }
    nextOffset -= length;
  }
  // 짧은 경로에도 방향 표시를 하나 남긴다.
  if (arrows.length === 0 && totalLength > 0 && totalLength < spacing) {
    let remaining = totalLength / 2;
    for (const { from, to, start, dx, dy, length } of segments) {
      if (remaining > length) {
        remaining -= length;
        continue;
      }
      const fraction = remaining / length;
      const x = start.x + dx * fraction;
      const y = start.y + dy * fraction;
      if (x >= -16 && x <= viewport.width + 16 && y >= -16 && y <= viewport.height + 16)
        arrows.push({
          coordinate: interpolatedCoordinateFor(from, to, fraction),
          rotation: Math.atan2(dy, dx) * (180 / Math.PI),
        });
      break;
    }
  }
  return arrows;
}
