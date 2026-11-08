import { describe, expect, it } from "@jest/globals";
import {
  routeArrowsFor,
  routeMapCoordinateForSequence,
  routeMapMotionPathFor,
  stopDistanceForZoom,
  stopOverlapForLabelZoom,
  stopSpreadOffsetsFor,
  stopSpreadRangeFor,
} from "./routeMapGeometry";
import type { RouteMapData } from "./routeMapModel";

describe("정류장 기준 줌 거리", () => {
  it.each([14, 16, 18, 13.5])("줌 %s의 화면 좌표를 줌 14의 같은 원형 중심 거리로 환산한다", (zoom) => {
    const scale = 2 ** (zoom - 14);
    const first = { x: 100 + 9 * scale, y: 200 + 12 * scale };
    const second = { x: 100, y: 200 };

    expect(stopDistanceForZoom(first, second, zoom, 14)).toBeCloseTo(15);
    expect(stopDistanceForZoom(second, first, zoom, 14)).toBeCloseTo(15);
  });
});

describe("이름표 기준 줌의 기본 아이콘 겹침", () => {
  it.each([14, 16, 18, 13.5])("줌 %s에서도 줌 14의 중심 거리가 24px 미만일 때만 겹친다", (zoom) => {
    const scale = 2 ** (zoom - 14);
    const first = { x: 9 * scale, y: 12 * scale };
    const second = { x: 0, y: 0 };

    expect(stopOverlapForLabelZoom(first, second, zoom)).toBe(true);
    expect(stopOverlapForLabelZoom(second, first, zoom)).toBe(true);
    expect(stopOverlapForLabelZoom({ x: 23.99 * scale, y: 0 }, second, zoom)).toBe(true);
    expect(stopOverlapForLabelZoom({ x: 24 * scale, y: 0 }, second, zoom)).toBe(false);
    expect(stopOverlapForLabelZoom({ x: 24.01 * scale, y: 0 }, second, zoom)).toBe(false);
  });
});

describe("마주 보는 정류장 밀림", () => {
  it.each([-16, 16])("UP의 가로 위치가 %spx일 때 좌우 순서를 유지하고 원형 영역을 벌린다", (horizontalDistance) => {
    const up = { x: horizontalDistance, y: 12 };
    const down = { x: 0, y: 0 };
    const offsets = stopSpreadOffsetsFor(up, down, 32);
    const spreadDistance = up.x + offsets.up - (down.x + offsets.down);

    expect(Math.sign(spreadDistance)).toBe(Math.sign(horizontalDistance));
    expect(Math.hypot(spreadDistance, up.y - down.y)).toBeCloseTo(32);
    expect(offsets.up + offsets.down).toBe(0);
  });

  it.each([-1, 1])("가로 경계의 %s 방향 안팎에서 밀림 차이가 1px 미만이다", (direction) => {
    const inside = stopSpreadOffsetsFor({ x: direction * 31.99, y: 0 }, { x: 0, y: 0 }, 32);
    const outside = stopSpreadOffsetsFor({ x: direction * 32.01, y: 0 }, { x: 0, y: 0 }, 32);

    expect(Math.abs(inside.up - outside.up)).toBeLessThan(1);
    expect(Math.abs(inside.down - outside.down)).toBeLessThan(1);
    expect(outside).toEqual({ up: 0, down: 0 });
  });

  it.each([-1, 1])("세로 경계의 %s 방향 안팎에서 밀림 차이가 1px 미만이다", (direction) => {
    const inside = stopSpreadOffsetsFor({ x: 0, y: direction * 31.99 }, { x: 0, y: 0 }, 32);
    const outside = stopSpreadOffsetsFor({ x: 0, y: direction * 32.01 }, { x: 0, y: 0 }, 32);

    expect(Math.abs(inside.up - outside.up)).toBeLessThan(1);
    expect(Math.abs(inside.down - outside.down)).toBeLessThan(1);
    expect(outside).toEqual({ up: 0, down: 0 });
  });

  it.each([
    { x: 32, y: 0 },
    { x: -32, y: 0 },
    { x: 0, y: 32 },
    { x: 0, y: -32 },
    { x: 24, y: 24 },
    { x: 50, y: 50 },
  ])("원형 영역이 겹치지 않는 위치 $x, $y에서는 밀지 않는다", (point) => {
    expect(stopSpreadOffsetsFor(point, { x: 0, y: 0 }, 32)).toEqual({ up: 0, down: 0 });
  });

  it("가로 위치가 같을 때만 UP을 오른쪽으로 벌린다", () => {
    expect(stopSpreadOffsetsFor({ x: 0, y: 0 }, { x: 0, y: 0 }, 32)).toEqual({ up: 16, down: -16 });
  });
});

describe("여러 정류장 짝의 밀림 누적", () => {
  it.each([
    { offsets: [], expected: 0 },
    { offsets: [0], expected: 0 },
    { offsets: [4, 8, 6], expected: 8 },
    { offsets: [-4, -8, -6], expected: -8 },
    { offsets: [3, 9, -4, -7, 5], expected: 2 },
  ])(
    "밀림 $offsets는 처리 순서와 무관하게 방향별 최댓값과 최솟값을 더해 $expected px이 된다",
    ({ offsets, expected }) => {
      const range = offsets.reduce(stopSpreadRangeFor, { left: 0, right: 0 });
      const reversed = [...offsets].reverse().reduce(stopSpreadRangeFor, { left: 0, right: 0 });

      expect(range.left + range.right).toBe(expected);
      expect(reversed).toEqual(range);
    },
  );
});

const data: RouteMapData = {
  routeId: "1",
  referenceVersionId: "1",
  paths: [
    {
      direction: "UP",
      coordinates: [
        { latitude: 37, longitude: 127 },
        { latitude: 38, longitude: 129 },
      ],
    },
  ],
  stops: [
    { sequence: 1, stopId: "1", direction: "UP", coordinate: { latitude: 37, longitude: 127 } },
    { sequence: 2, stopId: "2", direction: "UP", coordinate: { latitude: 38, longitude: 129 } },
  ],
};

describe("지도 차량 좌표", () => {
  it("native 시간 경로도 굴곡과 정류장별 속도 변화를 웹 보간과 동일하게 유지한다", () => {
    const curved: RouteMapData = {
      ...data,
      stops: [
        { sequence: 1, stopId: "1", direction: "UP", coordinate: { latitude: 0, longitude: 0 } },
        { sequence: 2, stopId: "2", direction: "UP", coordinate: { latitude: 2, longitude: 1 } },
        { sequence: 5, stopId: "3", direction: "UP", coordinate: { latitude: 0, longitude: 2 } },
      ],
      paths: [
        {
          direction: "UP",
          coordinates: [
            { latitude: 0, longitude: 0 },
            { latitude: 2, longitude: 0 },
            { latitude: 2, longitude: 1 },
            { latitude: 2, longitude: 2 },
            { latitude: 0, longitude: 2 },
          ],
        },
      ],
    };
    const path = routeMapMotionPathFor(curved, 1.2, 4.5, "UP");
    expect(path[0]?.progress).toBe(0);
    expect(path.at(-1)?.progress).toBe(1);
    for (let progress = 0; progress < 1; progress += 0.01) {
      const end = path.findIndex((point) => point.progress > progress);
      const from = path[Math.max(0, end - 1)]!;
      const to = path[end]!;
      const fraction = (progress - from.progress) / (to.progress - from.progress);
      const expected = routeMapCoordinateForSequence(curved, 1.2 + 3.3 * progress, "UP")!;
      expect(from.latitude + (to.latitude - from.latitude) * fraction).toBeCloseTo(expected.latitude, 8);
      expect(from.longitude + (to.longitude - from.longitude) * fraction).toBeCloseTo(expected.longitude, 8);
    }
    expect(routeMapMotionPathFor({ ...curved, paths: [] }, 1.2, 2, "UP")).toEqual([]);
    expect(routeMapMotionPathFor(curved, 3, 2, "UP")).toEqual([]);
  });

  it("같은 좌표를 반복 방문할 때 서버가 지정한 선분에서 차량 이동을 시작한다", () => {
    const origin = { latitude: 0, longitude: 0 };
    const end = { latitude: 1, longitude: 0 };
    const loop: RouteMapData = {
      ...data,
      paths: [{ direction: "UP", coordinates: [origin, { latitude: 0, longitude: 1 }, origin, end] }],
      stops: [
        {
          ...data.stops[0]!,
          coordinate: origin,
          pathConnection: { coordinate: origin, segmentIndex: 2, segmentFraction: 0 },
        },
        {
          ...data.stops[1]!,
          coordinate: end,
          pathConnection: { coordinate: end, segmentIndex: 2, segmentFraction: 1 },
        },
      ],
    };
    expect(routeMapCoordinateForSequence(loop, 1.5, "UP")).toEqual({ latitude: 0.5, longitude: 0 });
  });

  it("목록과 공유한 순번 진행률을 제공된 방향별 경로 위에서 보간한다", () => {
    expect(routeMapCoordinateForSequence(data, 1.25, "UP")).toEqual({ latitude: 37.25, longitude: 127.5 });
    expect(routeMapCoordinateForSequence(data, 0, "UP")).toEqual(data.stops[0]?.coordinate);
    expect(routeMapCoordinateForSequence(data, 3, "UP")).toEqual(data.stops[1]?.coordinate);
  });
  it("방향별 경로가 없으면 전체 도로가 있어도 정확히 보고된 정류장 좌표만 반환한다", () => {
    const unsplit: RouteMapData = {
      ...data,
      paths: [],
      fullPath: [
        { latitude: 37, longitude: 127 },
        { latitude: 39, longitude: 127 },
        { latitude: 38, longitude: 129 },
      ],
    };
    expect(routeMapCoordinateForSequence(unsplit, 1, "UP")).toEqual(data.stops[0]?.coordinate);
    expect(routeMapCoordinateForSequence(unsplit, 2, "UP")).toEqual(data.stops[1]?.coordinate);
    expect(routeMapCoordinateForSequence(unsplit, 1.25, "UP")).toBeNull();
    expect(routeMapCoordinateForSequence(unsplit, 0, "UP")).toBeNull();
    expect(routeMapCoordinateForSequence(unsplit, 3, "UP")).toBeNull();
    expect(routeMapCoordinateForSequence(unsplit, 1, "DOWN")).toBeNull();
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
