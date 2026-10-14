export type RouteMapPoint = { x: number; y: number };

export type RouteGeometry = {
  routeId: string;
  referenceVersionId: string;
  geometryRevision: string;
  name: string;
  startPoint: string;
  endPoint: string;
  status: "READY" | "PARTIAL" | "UNAVAILABLE";
  reason: "NOT_COLLECTED" | "MISSING_STOP_COORDINATES" | null;
  /** x를 경도, y를 위도로 표시하는 가정. 공급자가 WGS84를 보증한 값은 아니다. */
  coordinateSystem: "WGS84_ASSUMED";
  turningPoint: { stopSequence: number } | null;
  busStops: {
    stationId: string;
    stopSequence: number;
    name: string;
    direction: "UP" | "DOWN";
    boardingAllowed: boolean;
    point: RouteMapPoint | null;
  }[];
  points: RouteMapPoint[];
  boundary: { left: number; top: number; right: number; bottom: number } | null;
};
