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

// FE 표시 모델이다. 백엔드 지도 데이터 응답 계약은 아직 합의 전이다.
export interface RouteMapData {
  routeId: BoardRoute["id"];
  referenceVersionId: BoardRoute["referenceVersionId"];
  stops: RouteMapStop[];
  paths: DirectionPath[];
}
