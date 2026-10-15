import type { Board, Direction } from "@/shared/api/routeForecast.types";
import type { RouteMapData, RouteMapStop } from "./routeMapModel";
import { previewRoutes } from "./routeMapPreviewStops";

let lastKey: string | null = null;
let lastData: RouteMapData | null = null;

// 개발 전용: 실제 정류장 좌표를 순번대로 잇는 목업이다. 실제 도로 경로 API를 대신하지 않는다.
export function loadPreviewRouteMapData(board: Board): RouteMapData | null {
  const key = JSON.stringify([
    board.route.id,
    board.route.referenceVersionId,
    board.route.turnSequence,
    board.stops.map((stop) => [stop.sequence, stop.stopId, stop.name, stop.direction, stop.boardingAllowed]),
  ]);
  if (key === lastKey) return lastData;
  const snapshot = previewRoutes[board.route.id];
  let stops: RouteMapStop[] | null = null;
  if (
    snapshot !== undefined &&
    snapshot.turnSequence === board.route.turnSequence &&
    snapshot.stops.length === board.stops.length
  ) {
    const matches = snapshot.stops.every((row, index) => {
      const stop = board.stops[index];
      return (
        stop !== undefined &&
        row[0] === stop.sequence &&
        row[1] === stop.stopId &&
        row[2] === stop.name &&
        row[3] === stop.direction &&
        row[4] === stop.boardingAllowed
      );
    });
    if (matches)
      stops = snapshot.stops.map(([sequence, stopId, , direction, , latitude, longitude]) => ({
        sequence,
        stopId,
        direction,
        coordinate: { latitude, longitude },
      }));
  } else if (
    snapshot === undefined &&
    board.route.id === "200000001" &&
    board.stops.every((stop) => stop.stopId.length < 9)
  ) {
    // E2E의 가상 정류장과 짝을 이루는 개발 fixture만 지원한다.
    stops = board.stops.map((stop) => {
      const directionStops = board.stops.filter((item) => item.direction === stop.direction);
      const index = directionStops.findIndex((item) => item.sequence === stop.sequence);
      const progress = index / Math.max(1, directionStops.length - 1);
      const offset = stop.direction === "UP" ? progress : 1 - progress;
      return {
        sequence: stop.sequence,
        stopId: stop.stopId,
        direction: stop.direction,
        coordinate: {
          latitude: 37.395 + offset * 0.07,
          longitude: 127.105 + offset * 0.12 + (stop.direction === "DOWN" ? 0.002 : 0),
        },
      };
    });
  }
  lastKey = key;
  lastData =
    stops === null
      ? null
      : {
          routeId: board.route.id,
          referenceVersionId: board.route.referenceVersionId,
          stops,
          paths: (["UP", "DOWN"] as const)
            .filter((direction: Direction) => stops.some((stop) => stop.direction === direction))
            .map((direction) => ({
              direction,
              coordinates: stops.filter((stop) => stop.direction === direction).map((stop) => stop.coordinate),
            })),
        };
  return lastData;
}
