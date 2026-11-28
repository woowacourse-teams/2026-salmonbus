import { describe, expect, it } from "@jest/globals";
import type { ApiResult } from "@/shared/api/client";
import type { RouteGeometry } from "@/shared/api/routeGeometry.types";
import { routeGeometryMapDataFor } from "../../verdict-board/map/routeGeometryMapData";
import { fullPathForDisplay } from "../../verdict-board/map/routeMapGeometry";
import { favoriteMapRoutesOf, nextGeometryPollFrom, overviewPathsOf } from "./favoriteMapRoutes";

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
    busStops: [],
    points: [
      { x: 127, y: 37 },
      { x: 127.1, y: 37.1 },
      { x: 127, y: 37 },
    ],
    paths: {
      UP: {
        points: [
          { x: 127, y: 37 },
          { x: 127.1, y: 37.1 },
        ],
      },
      DOWN: {
        points: [
          { x: 127.1, y: 37.1 },
          { x: 127, y: 37 },
        ],
      },
    },
    boundary: { left: 127, top: 37.1, right: 127.1, bottom: 37 },
    ...overrides,
  };
}

function mapDataFor(geometry: RouteGeometry) {
  const data = routeGeometryMapDataFor(geometry, route);
  if (data === null) throw new Error("지도에 그릴 형상을 만들지 못했습니다.");
  return data;
}

function received(body: RouteGeometry, maxAgeSeconds: number): ApiResult<RouteGeometry> {
  return {
    ok: true,
    body,
    status: 200,
    requestId: null,
    clock: null,
    lifetime: { noStore: false, maxAgeSeconds, ageSeconds: 0 },
  };
}

describe("favoriteMapRoutesOf", () => {
  it("같은 노선의 정류장을 여러 개 저장했으면 노선을 한 번만 담고 먼저 저장한 방면을 쓴다", () => {
    const routes = favoriteMapRoutesOf([
      { routeId: "200000001", direction: "DOWN" },
      { routeId: "200000002", direction: "UP" },
      { routeId: "200000001", direction: "UP" },
    ]);

    expect([...routes]).toEqual([
      ["200000001", "DOWN"],
      ["200000002", "UP"],
    ]);
  });

  it("저장한 정류장이 없으면 그릴 노선도 없다", () => {
    expect(favoriteMapRoutesOf([]).size).toBe(0);
  });
});

describe("nextGeometryPollFrom", () => {
  it("형상을 받았으면 응답의 유효 시간이 지나도 다시 조회하지 않는다", () => {
    expect(nextGeometryPollFrom(received(geometryWith(), 5))).toEqual({ kind: "stop" });
  });

  it("표시를 제한한 노선도 다시 조회하지 않는다", () => {
    const withheld = geometryWith({ status: "UNAVAILABLE", reason: "DISPLAY_WITHHELD", points: [], paths: null });

    expect(nextGeometryPollFrom(received(withheld, 5))).toEqual({ kind: "stop" });
  });

  it.each(["GENERATION_PENDING", "NOT_COLLECTED"] as const)("%s이면 응답의 유효 시간 뒤에 다시 조회한다", (reason) => {
    const pending = geometryWith({ status: "UNAVAILABLE", reason, points: [], paths: null });

    expect(nextGeometryPollFrom(received(pending, 5))).toEqual({ kind: "again", delayMs: 5_000 });
  });

  it("연결에 실패하면 다시 조회한다", () => {
    expect(nextGeometryPollFrom({ ok: false, failure: { kind: "network", cause: null } })).toMatchObject({
      kind: "again",
    });
  });
});

describe("overviewPathsOf", () => {
  it("두 방향 경로가 다 있으면 두 경로만 [경도, 위도] 순서로 돌려준다", () => {
    const data = mapDataFor(geometryWith());

    expect(overviewPathsOf(data, fullPathForDisplay(data))).toEqual([
      [
        [127, 37],
        [127.1, 37.1],
      ],
      [
        [127.1, 37.1],
        [127, 37],
      ],
    ]);
  });

  it("한 방향 경로가 빠진 노선은 전체 경로까지 범위에 넣는다", () => {
    const geometry = geometryWith();
    geometry.paths!.DOWN.points = [];
    geometry.points.push({ x: 127.6, y: 37.45 });
    const data = mapDataFor(geometry);

    const paths = overviewPathsOf(data, fullPathForDisplay(data));

    expect(paths).toHaveLength(2);
    expect(Math.max(...paths.flat().map(([longitude]) => longitude))).toBe(127.6);
  });

  it("점이 하나뿐인 경로는 뺀다", () => {
    const data = mapDataFor(geometryWith());
    data.paths[1]!.coordinates = [{ longitude: 127.1, latitude: 37.1 }];

    expect(overviewPathsOf(data, undefined)).toHaveLength(1);
  });

  it("형상이 없으면 빈 목록을 돌려준다", () => {
    expect(overviewPathsOf(null, undefined)).toEqual([]);
  });
});
