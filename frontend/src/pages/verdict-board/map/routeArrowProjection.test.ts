import { describe, expect, it } from "@jest/globals";
import { createRouteArrowProjection } from "./routeArrowProjection";
import type { NaverCoordinate } from "@/shared/map/naverSdk";

class Coordinate implements NaverCoordinate {
  static created = 0;
  constructor(
    private readonly latitude: number,
    private readonly longitude: number,
  ) {
    Coordinate.created += 1;
  }
  lat() {
    return this.latitude;
  }
  lng() {
    return this.longitude;
  }
}

function setup(coordinates = Array.from({ length: 200 }, (_, i) => new Coordinate(i / 200, i / 200))) {
  const state = {
    angle: 0,
    pan: 0,
    tilt: 0,
    zoom: 15,
    width: 425,
    gl: true,
    projection: "EPSG:3857",
    perspective: 0,
    calls: 0,
    projected: [] as NaverCoordinate[],
  };
  const project = (c: NaverCoordinate) => {
    const scale = 2 ** (state.zoom - 15);
    const x = c.lng() * 100 * scale,
      y = ((-Math.asinh(Math.tan((c.lat() * Math.PI) / 180)) * 180) / Math.PI) * 100 * scale;
    const rx = x * Math.cos(state.angle) - y * Math.sin(state.angle) + state.pan;
    const ry = x * Math.sin(state.angle) + y * Math.cos(state.angle);
    return { x: 200 + rx / (1 + state.perspective * ry), y: 300 + ry / (1 + state.perspective * ry) };
  };
  const map = {
    getZoom: () => state.zoom,
    getSize: () => ({ width: state.width, height: 792 }),
    getOptions: () => state.gl,
    getTilt: () => state.tilt,
    getProjection: () => ({
      getProjectionName: () => state.projection,
      fromCoordToOffset: (c: NaverCoordinate) => {
        state.calls++;
        state.projected.push(c);
        return project(c);
      },
      fromOffsetToCoord: (p: { x: number; y: number }) => new Coordinate(p.y, p.x),
    }),
  };
  const cache = createRouteArrowProjection({ LatLng: Coordinate }, coordinates);
  const verify = () => {
    const current = cache.frame(map);
    coordinates.forEach((coordinate, index) => {
      const actual = current(index),
        expected = project(coordinate);
      expect(actual.x).toBeCloseTo(expected.x, 9);
      expect(actual.y).toBeCloseTo(expected.y, 9);
    });
  };
  return { state, coordinates, map, cache, verify };
}

describe("노선 화살표 투영 재사용", () => {
  it("세계 좌표를 준비할 때 경로의 모든 점을 SDK 화면 좌표로 변환하지 않는다", () => {
    const { verify, state, coordinates } = setup();
    verify();
    expect(state.calls).toBeLessThan(coordinates.length / 2);
    expect(state.projected.some((point) => coordinates.some((original) => point === original))).toBe(false);
  });
  it.each([10, 13.9, 14, 15.4, 19, 20])("줌 %s의 확대·회전·이동에서도 세계 좌표를 재사용한다", (zoom) => {
    const { verify, state, coordinates } = setup();
    verify();
    state.zoom = zoom;
    state.angle = Math.PI / 5;
    state.pan = -76;
    state.calls = 0;
    verify();
    expect(state.calls).toBeLessThan(coordinates.length / 2);
  });
  it("평면 회전과 이동에서는 같은 좌표를 유지하고 전체 SDK 호출을 줄인다", () => {
    const { verify, state, coordinates } = setup();
    verify();
    state.calls = 0;
    state.angle = Math.PI / 3;
    state.pan = 32;
    verify();
    expect(state.calls).toBeLessThan(coordinates.length / 2);
  });
  it.each(["zoom", "width"] as const)("%s 변경에도 같은 세계 좌표를 재사용한다", (key) => {
    const { verify, state, coordinates } = setup();
    verify();
    state[key] += 1;
    state.calls = 0;
    verify();
    expect(state.calls).toBeLessThan(coordinates.length / 2);
  });
  it.each(["tilt", "projection", "gl"] as const)("지원하지 않는 %s 상태는 직접 계산한다", (key) => {
    const { verify, state, coordinates } = setup();
    verify();
    if (key === "tilt") state.tilt = 20;
    if (key === "projection") state.projection = "custom";
    if (key === "gl") state.gl = false;
    state.calls = 0;
    verify();
    expect(state.calls).toBe(coordinates.length);
  });
  it("제어점의 관계가 회전·이동이 아니면 직접 계산한다", () => {
    const { verify, state, coordinates } = setup();
    verify();
    state.perspective = 0.002;
    state.angle = 0.4;
    state.calls = 0;
    verify();
    expect(state.calls).toBeGreaterThanOrEqual(coordinates.length);
  });
  it("화살표가 가시 영역의 급꺾임 경계에 놓이면 직접 계산한다", () => {
    const { verify, state, coordinates } = setup([
      new Coordinate(0, 0),
      new Coordinate(0, 0.5),
      new Coordinate(0.5, 0.5),
    ]);
    verify();
    state.calls = 0;
    state.projected.length = 0;
    verify();
    expect(state.projected.filter((coordinate) => coordinates.some((original) => original === coordinate))).toEqual(
      coordinates,
    );
  });
  it("정리하면 재사용 자료를 해제하고 다음 갱신에서 제어점을 다시 준비한다", () => {
    const { cache, verify } = setup();
    Coordinate.created = 0;
    verify();
    const prepared = Coordinate.created;
    verify();
    expect(Coordinate.created).toBe(prepared);
    cache.clear();
    verify();
    expect(Coordinate.created).toBe(prepared * 2);
  });
});
