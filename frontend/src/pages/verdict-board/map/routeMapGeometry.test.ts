import { describe, expect, it } from "@jest/globals";
import { routeArrowsFor, routeMapCoordinateForSequence } from "./routeMapGeometry";
import type { RouteMapData } from "./routeMapModel";

const data: RouteMapData = {
  routeId: "1",
  referenceVersionId: "1",
  paths: [],
  stops: [
    { sequence: 1, stopId: "1", direction: "UP", coordinate: { latitude: 37, longitude: 127 } },
    { sequence: 2, stopId: "2", direction: "UP", coordinate: { latitude: 38, longitude: 129 } },
  ],
};

describe("지도 차량 좌표", () => {
  it("목록과 공유한 순번 진행률을 정류장 좌표로 보간한다", () => {
    expect(routeMapCoordinateForSequence(data, 1.25, "UP")).toEqual({ latitude: 37.25, longitude: 127.5 });
    expect(routeMapCoordinateForSequence(data, 0, "UP")).toEqual(data.stops[0]?.coordinate);
    expect(routeMapCoordinateForSequence(data, 3, "UP")).toEqual(data.stops[1]?.coordinate);
  });
  it("좌표가 없거나 유효하지 않은 순번이면 위치를 만들지 않는다", () => {
    expect(routeMapCoordinateForSequence({ ...data, stops: [] }, 1, "UP")).toBeNull();
    expect(routeMapCoordinateForSequence(data, Number.NaN, "UP")).toBeNull();
    expect(routeMapCoordinateForSequence(data, 1, "DOWN")).toBeNull();
  });
  it("연속하지 않고 뒤섞인 순번을 정렬하고 꺾인 경로의 누적 거리로 이동한다", () => {
    const curved: RouteMapData = {
      ...data,
      stops: [
        { sequence: 9, stopId: "end", direction: "UP", coordinate: { latitude: 0, longitude: 2 } },
        { sequence: 3, stopId: "other", direction: "DOWN", coordinate: { latitude: 10, longitude: 10 } },
        { sequence: 3, stopId: "start", direction: "UP", coordinate: { latitude: 0, longitude: 0 } },
      ],
      paths: [
        {
          direction: "UP",
          coordinates: [
            { latitude: 0, longitude: 0 },
            { latitude: 2, longitude: 0 },
            { latitude: 2, longitude: 2 },
            { latitude: 0, longitude: 2 },
          ],
        },
      ],
    };
    // 두 정류장의 직선 중점(0, 1)이 아닌 실제 경로 중점이다.
    expect(routeMapCoordinateForSequence(curved, 6, "UP")).toEqual({ latitude: 2, longitude: 1 });
    expect(routeMapCoordinateForSequence(curved, 4, "UP")).toEqual({ latitude: 1, longitude: 0 });
    expect(routeMapCoordinateForSequence(curved, 3, "DOWN")).toEqual({ latitude: 10, longitude: 10 });
  });
  it("같은 좌표를 다시 지나는 경로에서도 정류장 진행 순서 이전으로 투영하지 않는다", () => {
    const loop: RouteMapData = {
      ...data,
      stops: [
        { sequence: 1, stopId: "first", direction: "UP", coordinate: { latitude: 0, longitude: 0 } },
        { sequence: 2, stopId: "turn", direction: "UP", coordinate: { latitude: 0, longitude: 2 } },
        { sequence: 3, stopId: "last", direction: "UP", coordinate: { latitude: 0, longitude: 0 } },
      ],
      paths: [
        {
          direction: "UP",
          coordinates: [
            { latitude: 0, longitude: 0 },
            { latitude: 0, longitude: 2 },
            { latitude: 2, longitude: 2 },
            { latitude: 2, longitude: 0 },
            { latitude: 0, longitude: 0 },
          ],
        },
      ],
    };
    expect(routeMapCoordinateForSequence(loop, 2.5, "UP")).toEqual({ latitude: 2, longitude: 1 });
    expect(routeMapCoordinateForSequence(loop, 3, "UP")).toEqual({ latitude: 0, longitude: 0 });
  });
  it("정류장이 경로 선에서 조금 벗어나 있어도 선 위로 투영하고 길이 없는 구간을 건너뛴다", () => {
    const offset: RouteMapData = {
      ...data,
      stops: [
        { sequence: 1, stopId: "1", direction: "UP", coordinate: { latitude: 0.1, longitude: 0.5 } },
        { sequence: 4, stopId: "4", direction: "UP", coordinate: { latitude: -0.1, longitude: 1.5 } },
      ],
      paths: [
        {
          direction: "UP",
          coordinates: [
            { latitude: 0, longitude: 0 },
            { latitude: 0, longitude: 0 },
            { latitude: 0, longitude: 2 },
          ],
        },
      ],
    };
    expect(routeMapCoordinateForSequence(offset, 2.5, "UP")).toEqual({ latitude: 0, longitude: 1 });
  });
});

describe("노선 방향 화살표", () => {
  const coordinates = [
    { latitude: 0, longitude: 0 },
    { latitude: 0, longitude: 2 },
    { latitude: 0, longitude: 4 },
  ];
  const viewport = { width: 500, height: 100 };
  it("정류장마다가 아니라 화면의 일정 간격으로 배치한다", () => {
    const arrows = routeArrowsFor(coordinates, (c) => ({ x: c.longitude * 100, y: 20 }), viewport);
    expect(arrows.map((a) => a.coordinate.longitude)).toEqual([0.5, 1.5, 2.5, 3.5]);
    expect(arrows.every((a) => a.rotation === 0)).toBe(true);
  });
  it("축소해도 화살표를 유지하고 역방향은 반대로 향한다", () => {
    expect(routeArrowsFor(coordinates, (c) => ({ x: c.longitude * 10, y: 20 }), viewport)).toHaveLength(1);
    const reverse = routeArrowsFor([...coordinates].reverse(), (c) => ({ x: c.longitude * 100, y: 20 }), viewport);
    expect(reverse.every((a) => a.rotation === 180)).toBe(true);
  });
  it("지도 밖의 경로와 길이 없는 구간에 화살표를 만들지 않는다", () => {
    expect(routeArrowsFor(coordinates, (c) => ({ x: c.longitude * 100 + 1000, y: 20 }), viewport)).toEqual([]);
    expect(routeArrowsFor([coordinates[0]!, coordinates[0]!], () => ({ x: 20, y: 20 }), viewport)).toEqual([]);
  });
  it("짧고 꺾인 경로의 화살표도 경로 위에 남긴다", () => {
    const arrows = routeArrowsFor(
      [
        { latitude: 0, longitude: 0 },
        { latitude: 1, longitude: 0 },
        { latitude: 1, longitude: 3 },
      ],
      (coordinate) => ({ x: 20 + coordinate.longitude * 10, y: 20 + coordinate.latitude * 10 }),
      viewport,
    );
    expect(arrows).toEqual([{ coordinate: { latitude: 1, longitude: 1 }, rotation: 0 }]);
  });
});
