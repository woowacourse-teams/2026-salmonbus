import type { BoardRoute, Direction, StopState } from "@/shared/api/routeForecast.types";

export interface GeoCoordinate {
  latitude: number;
  longitude: number;
}

export interface RouteMapStop {
  sequence: StopState["sequence"];
  stopId: StopState["stopId"];
  direction: Direction;
  coordinate: GeoCoordinate;
}

export interface DirectionPath {
  direction: Direction;
  coordinates: GeoCoordinate[];
}

// geometry API의 좌표와 방향별 경로를 지도 표시에 맞게 변환한 FE 모델이다.
export interface RouteMapData {
  routeId: BoardRoute["id"];
  referenceVersionId: BoardRoute["referenceVersionId"];
  stops: RouteMapStop[];
  paths: DirectionPath[];
  // API가 제공한 전체 도로 형상. 방향을 나눌 수 없어도 원본 운행 순서를 유지한다.
  fullPath?: GeoCoordinate[];
}
