import type { BoardRoute, Direction } from "@/shared/api/routeForecast.types";
import type { GeoCoordinate, RouteMapData, RouteMapStop } from "./routeMapModel";

export const DETAIL_ZOOM = 14;
export const DEFAULT_STOP_ICON_SIZE = 24;

export function fullPathForDisplay(
  data: RouteMapData,
  directions?: BoardRoute["directions"],
): GeoCoordinate[] | undefined {
  const { fullPath, paths } = data;
  if (!fullPath || fullPath.length < 2) return undefined;

  const mappedDirections = new Set(paths.map(({ direction }) => direction));
  const missingDirection = directions?.some(({ id }) => !mappedDirections.has(id)) ?? mappedDirections.size < 2;
  return missingDirection ? fullPath : undefined;
}

export interface ScreenPoint {
  x: number;
  y: number;
}

export function stopDistanceForZoom(first: ScreenPoint, second: ScreenPoint, zoom: number, targetZoom: number): number {
  return Math.hypot(first.x - second.x, first.y - second.y) * 2 ** (targetZoom - zoom);
}

export function stopOverlapForLabelZoom(first: ScreenPoint, second: ScreenPoint, zoom: number): boolean {
  // 줌과 선택 변경으로 이름표가 움직이지 않도록 기준 줌의 기본 아이콘만 비교하고 밀림 여유 간격은 제외한다.
  return stopDistanceForZoom(first, second, zoom, DETAIL_ZOOM) < DEFAULT_STOP_ICON_SIZE;
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

/** Send one timed road path per observation, rather than coordinates every animation frame. */
export function routeMapMotionPathFor(
  data: RouteMapData,
  start: number,
  end: number,
  direction: Direction,
): (GeoCoordinate & { progress: number })[] {
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return [];
  const first = routeMapCoordinateForSequence(data, start, direction);
  const last = routeMapCoordinateForSequence(data, end, direction);
  if (first === null || last === null) return [];
  if (start === end)
    return [
      { ...first, progress: 0 },
      { ...last, progress: 1 },
    ];
  const prepared = pathCache.get(data)?.get(direction);
  const sequences = new Set([start, end]);
  if (prepared !== undefined) {
    for (let index = 0; index < prepared.stopSequences.length - 1; index += 1) {
      const fromSequence = prepared.stopSequences[index]!;
      const toSequence = prepared.stopSequences[index + 1]!;
      const fromDistance = prepared.stopDistances[index]!;
      const toDistance = prepared.stopDistances[index + 1]!;
      if (fromSequence > start && fromSequence < end) sequences.add(fromSequence);
      if (toSequence > start && toSequence < end) sequences.add(toSequence);
      if (toSequence < start || fromSequence > end || toDistance <= fromDistance) continue;
      for (const distance of prepared.distances) {
        if (distance <= fromDistance || distance >= toDistance) continue;
        const sequence =
          fromSequence + ((distance - fromDistance) / (toDistance - fromDistance)) * (toSequence - fromSequence);
        if (sequence > start && sequence < end) sequences.add(sequence);
      }
    }
  }
  return Array.from(sequences)
    .sort((a, b) => a - b)
    .flatMap((sequence) => {
      const coordinate = routeMapCoordinateForSequence(data, sequence, direction);
      return coordinate === null ? [] : [{ ...coordinate, progress: (sequence - start) / (end - start) }];
    });
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
  const stopSequences = stops.map((stop) => stop.sequence);
  const connectionDistances = stops.map(({ pathConnection }) => {
    if (pathConnection === undefined) return NaN;
    const { segmentIndex, segmentFraction } = pathConnection;
    const start = distances[segmentIndex] ?? NaN;
    const end = distances[segmentIndex + 1] ?? NaN;
    return start + (end - start) * segmentFraction;
  });
  if (
    connectionDistances.every(
      (distance, index) => Number.isFinite(distance) && distance >= (connectionDistances[index - 1] ?? 0),
    )
  ) {
    return { coordinates, distances, stopSequences, stopDistances: connectionDistances };
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
  return { coordinates, distances, stopSequences, stopDistances };
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
export const ROUTE_ARROW_SPACING_PX = 100;
// 화면 가장자리에 걸친 화살표도 그리도록 화면 밖 이 거리까지 배치한다.
export const ROUTE_ARROW_VIEWPORT_MARGIN_PX = 16;

function isNearViewport(x: number, y: number, viewport: { width: number; height: number }) {
  const margin = ROUTE_ARROW_VIEWPORT_MARGIN_PX;
  return x >= -margin && x <= viewport.width + margin && y >= -margin && y <= viewport.height + margin;
}

export function routeArrowsFor(
  coordinates: readonly GeoCoordinate[],
  project: (coordinate: GeoCoordinate, index: number) => ScreenPoint,
  viewport: { width: number; height: number },
): RouteArrow[] {
  const spacing = ROUTE_ARROW_SPACING_PX;
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
  let previousEnd: ScreenPoint | undefined;
  let previousIndex = -1;
  for (let index = 1; index < coordinates.length; index += 1) {
    const from = coordinates[index - 1];
    const to = coordinates[index];
    if (from === undefined || to === undefined) continue;
    const start = previousIndex === index - 1 && previousEnd !== undefined ? previousEnd : project(from, index - 1);
    const end = project(to, index);
    previousEnd = end;
    previousIndex = index;
    const dx = end.x - start.x;
    const dy = end.y - start.y;
    const length = Math.hypot(dx, dy);
    if (!Number.isFinite(length) || length === 0) continue;
    totalLength += length;
    if (totalLength < spacing) segments.push({ from, to, start, dx, dy, length });
    else if (segments.length > 0) segments.length = 0;
    for (; nextOffset < length; nextOffset += spacing) {
      const fraction = nextOffset / length;
      const x = start.x + dx * fraction;
      const y = start.y + dy * fraction;
      if (!isNearViewport(x, y, viewport)) continue;
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
      if (isNearViewport(x, y, viewport))
        arrows.push({
          coordinate: interpolatedCoordinateFor(from, to, fraction),
          rotation: Math.atan2(dy, dx) * (180 / Math.PI),
        });
      break;
    }
  }
  return arrows;
}
