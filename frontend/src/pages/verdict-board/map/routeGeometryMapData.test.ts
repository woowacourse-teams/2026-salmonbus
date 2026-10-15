import { describe, expect, it } from "@jest/globals";
import type { RouteGeometry } from "@/shared/api/routeGeometry.types";
import { routeGeometryMapDataFor } from "./routeGeometryMapData";

const route = { id: "200000001", referenceVersionId: "12" };

function geometryWith(overrides: Partial<RouteGeometry> = {}): RouteGeometry {
  return {
    routeId: route.id,
    referenceVersionId: route.referenceVersionId,
    geometryRevision: "geometry-1",
    name: "8100",
    startPoint: "기점",
    endPoint: "회차점",
    status: "READY",
    reason: null,
    coordinateSystem: "WGS84_ASSUMED",
    turningPoint: { stopSequence: 2 },
    busStops: [
      {
        stationId: "first",
        stopSequence: 1,
        name: "기점",
        direction: "UP",
        boardingAllowed: true,
        point: { x: 127, y: 37 },
      },
      {
        stationId: "turn",
        stopSequence: 2,
        name: "회차점",
        direction: "UP",
        boardingAllowed: true,
        point: { x: 127.1, y: 37.1 },
      },
      {
        stationId: "first",
        stopSequence: 3,
        name: "기점",
        direction: "DOWN",
        boardingAllowed: true,
        point: { x: 127, y: 37 },
      },
    ],
    points: [
      { x: 127, y: 37 },
      { x: 127.1, y: 37.1 },
      { x: 127, y: 37 },
    ],
    paths: {
      UP: {
        points: [
          { x: 127, y: 37 },
          { x: 127.05, y: 37.08 },
          { x: 127.1, y: 37.1 },
        ],
      },
      DOWN: {
        points: [
          { x: 127.1, y: 37.1 },
          { x: 127.08, y: 37.02 },
          { x: 127, y: 37 },
        ],
      },
    },
    boundary: { left: 127, top: 37.1, right: 127.1, bottom: 37 },
    ...overrides,
  };
}

describe("routeGeometryMapDataFor", () => {
  it("x를 경도, y를 위도로 바꾸고 상·하행의 서로 다른 도로 형상과 운행 순서를 유지한다", () => {
    const geometry = geometryWith();
    const data = routeGeometryMapDataFor(geometry, route);

    expect(data?.paths).toEqual([
      {
        direction: "UP",
        coordinates: [
          { latitude: 37, longitude: 127 },
          { latitude: 37.08, longitude: 127.05 },
          { latitude: 37.1, longitude: 127.1 },
        ],
      },
      {
        direction: "DOWN",
        coordinates: [
          { latitude: 37.1, longitude: 127.1 },
          { latitude: 37.02, longitude: 127.08 },
          { latitude: 37, longitude: 127 },
        ],
      },
    ]);
    expect(data?.stops.map(({ sequence, stopId, direction }) => ({ sequence, stopId, direction }))).toEqual([
      { sequence: 1, stopId: "first", direction: "UP" },
      { sequence: 2, stopId: "turn", direction: "UP" },
      { sequence: 3, stopId: "first", direction: "DOWN" },
    ]);
    expect(data?.fullPath).toEqual([
      { latitude: 37, longitude: 127 },
      { latitude: 37.1, longitude: 127.1 },
      { latitude: 37, longitude: 127 },
    ]);
    expect(data?.stops[0]?.coordinate).toEqual({ latitude: 37, longitude: 127 });
    expect(geometry.paths?.UP.points[1]).toEqual({ x: 127.05, y: 37.08 });
  });

  it.each([{ routeId: "200000002" }, { referenceVersionId: "13" }])(
    "보드의 노선이나 판본과 다르면 표시하지 않는다: %o",
    (overrides) => {
      expect(routeGeometryMapDataFor(geometryWith(overrides), route)).toBeNull();
    },
  );

  it("PARTIAL 응답은 좌표가 없는 정류장만 빼고 실제 도로 형상은 유지한다", () => {
    const geometry = geometryWith({ status: "PARTIAL", reason: "MISSING_STOP_COORDINATES" });
    geometry.busStops[1]!.point = null;
    const data = routeGeometryMapDataFor(geometry, route);
    expect(data?.stops.map((stop) => stop.sequence)).toEqual([1, 3]);
    expect(data?.paths[0]?.coordinates).toHaveLength(3);
    expect(data?.paths[1]?.coordinates).toHaveLength(3);
  });

  it.each(["READY", "PARTIAL", "UNAVAILABLE"] as const)(
    "%s 상태여도 방향 경로가 null이면 제공된 전체 points를 보존하고 임의 방향 경로는 만들지 않는다",
    (status) => {
      const data = routeGeometryMapDataFor(geometryWith({ status, paths: null }), route);
      expect(data?.paths).toEqual([]);
      expect(data?.fullPath).toEqual([
        { latitude: 37, longitude: 127 },
        { latitude: 37.1, longitude: 127.1 },
        { latitude: 37, longitude: 127 },
      ]);
      expect(data?.stops.map((stop) => stop.sequence)).toEqual([1, 2, 3]);
    },
  );

  it("실제 노선처럼 도로 형상 미수집 상태여도 제공된 정류장 좌표는 표시하고 경로를 만들지 않는다", () => {
    const geometry = geometryWith({ status: "UNAVAILABLE", reason: "NOT_COLLECTED", points: [], paths: null });
    geometry.busStops[0] = {
      stationId: "205000227",
      stopSequence: 1,
      name: "도촌동9단지앞",
      direction: "UP",
      boardingAllowed: true,
      point: { x: 127.1627833, y: 37.4118167 },
    };
    const data = routeGeometryMapDataFor(geometry, route);
    expect(data?.stops[0]).toEqual({
      stopId: "205000227",
      sequence: 1,
      direction: "UP",
      coordinate: { latitude: 37.4118167, longitude: 127.1627833 },
    });
    expect(data?.stops).toHaveLength(3);
    expect(data?.paths).toEqual([]);
    expect(data?.fullPath).toEqual([]);
  });

  it.each([
    { status: "NOT_READY" as RouteGeometry["status"] },
    { coordinateSystem: "UNKNOWN" as RouteGeometry["coordinateSystem"] },
  ])("알 수 없는 상태나 좌표계이면 표시하지 않는다: %o", (overrides) => {
    expect(routeGeometryMapDataFor(geometryWith(overrides), route)).toBeNull();
  });

  it("유효하지 않은 정류장 좌표를 제외하되 정류장 좌표가 하나도 없어도 실제 도로 경로는 유지한다", () => {
    const geometry = geometryWith();
    geometry.busStops[0]!.point = { x: Number.NaN, y: 37 };
    geometry.busStops[1]!.point = { x: 181, y: 37 };
    expect(routeGeometryMapDataFor(geometry, route)?.stops.map((stop) => stop.sequence)).toEqual([3]);
    geometry.busStops[2]!.point = { x: 127, y: -91 };
    const roadsOnly = routeGeometryMapDataFor(geometry, route);
    expect(roadsOnly?.stops).toEqual([]);
    expect(roadsOnly?.paths).toHaveLength(2);
    expect(roadsOnly?.fullPath).toHaveLength(3);
    geometry.paths = null;
    expect(routeGeometryMapDataFor(geometry, route)?.fullPath).toHaveLength(3);
    geometry.points = [];
    expect(routeGeometryMapDataFor(geometry, route)).toBeNull();
  });

  it.each([
    {
      points: [
        { x: 127, y: 37 },
        { x: Number.NaN, y: 37.05 },
        { x: 127.1, y: 37.1 },
      ],
    },
    { points: [{ x: 127, y: 37 }] },
    {
      points: [
        { x: 127, y: 37 },
        { x: 127, y: 37 },
      ],
    },
  ])("잘못되었거나 길이가 없는 방향 경로를 직선으로 대체하지 않고 나머지 방향만 표시한다", ({ points }) => {
    const geometry = geometryWith({ status: "PARTIAL" });
    geometry.paths!.UP.points = points;
    const data = routeGeometryMapDataFor(geometry, route);
    expect(data?.paths.map((path) => path.direction)).toEqual(["DOWN"]);
    expect(data?.stops.map((stop) => stop.direction)).toEqual(["UP", "UP", "DOWN"]);
    geometry.paths!.DOWN.points = [];
    const stopsOnly = routeGeometryMapDataFor(geometry, route);
    expect(stopsOnly?.paths).toEqual([]);
    expect(stopsOnly?.stops).toHaveLength(3);
  });

  it("분리 경로가 없어도 전체 도로의 굴곡과 반복 좌표를 원순서로 보존하고 정류장 직선으로 대체하지 않는다", () => {
    const geometry = geometryWith({
      paths: null,
      points: [
        { x: 127, y: 37 },
        { x: 127.03, y: 37.08 },
        { x: 127.03, y: 37.08 },
        { x: 127.09, y: 37.06 },
        { x: 127, y: 37 },
      ],
    });
    const data = routeGeometryMapDataFor(geometry, route);
    expect(data?.paths).toEqual([]);
    expect(data?.fullPath).toEqual([
      { latitude: 37, longitude: 127 },
      { latitude: 37.08, longitude: 127.03 },
      { latitude: 37.08, longitude: 127.03 },
      { latitude: 37.06, longitude: 127.09 },
      { latitude: 37, longitude: 127 },
    ]);
  });

  it("전체 경로 중간에 잘못된 좌표가 있으면 건너뛴 도로를 연결하지 않고 정류장만 유지한다", () => {
    const geometry = geometryWith({ paths: null });
    geometry.points[1] = { x: Number.NaN, y: 37 };
    const data = routeGeometryMapDataFor(geometry, route);
    expect(data?.fullPath).toEqual([]);
    expect(data?.paths).toEqual([]);
    expect(data?.stops).toHaveLength(3);
  });

  it("한 방향 정류장 좌표가 전부 없어도 두 방향의 실제 형상은 유지한다", () => {
    const geometry = geometryWith({ status: "PARTIAL" });
    geometry.busStops[2]!.point = null;
    const data = routeGeometryMapDataFor(geometry, route);
    expect(data?.stops.map((stop) => stop.direction)).toEqual(["UP", "UP"]);
    expect(data?.paths.map((path) => path.direction)).toEqual(["UP", "DOWN"]);
  });
});
