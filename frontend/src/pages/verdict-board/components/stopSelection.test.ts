import { describe, expect, it } from "@jest/globals";
import { expandableRowOf, stopSelectionFrom } from "./stopSelection";
import type { StopView } from "./displayPolicy";
import type { Board } from "@/shared/api/routeForecast.types";
import { boardMock } from "@/shared/api/routeForecast.mock";

describe("로그인 후 정류장 복귀", () => {
  it("현재 방면에 승차 가능한 방문 하나가 있을 때만 복원한다", () => {
    const stop = boardMock.stops.find((candidate) => candidate.boardingAllowed)!;
    expect(stopSelectionFrom(boardMock, stop.direction, stop.stopId)).toEqual({
      direction: stop.direction,
      sequence: stop.sequence,
    });
    expect(stopSelectionFrom(boardMock, stop.direction, "deleted-stop")).toBeNull();
  });
  it("같은 방면의 중복 방문은 임의로 선택하지 않는다", () => {
    const stop = boardMock.stops.find((candidate) => candidate.boardingAllowed)!;
    const board: Board = { ...boardMock, stops: [stop, { ...stop, sequence: stop.sequence + 1 }] };
    expect(stopSelectionFrom(board, stop.direction, stop.stopId)).toBeNull();
  });
});

describe("지도에서 고른 정류장의 목록 행", () => {
  const stops: StopView[] = [
    { kind: "passThrough", sequence: 1, stopId: "S1", name: "경유 정류장", isTurnaround: false },
    { kind: "noVehicle", sequence: 2, stopId: "S2", name: "정류장2", isTurnaround: false },
  ];

  it("경유 정류장 행까지 센 목록 위치와 펼칠 정류장을 찾는다", () => {
    expect(expandableRowOf(stops, 2)).toEqual({ stop: stops[1], rowPosition: 1 });
  });

  it("경유 정류장이나 목록에 없는 정류장은 펼침으로 세지 않는다", () => {
    expect(expandableRowOf(stops, 1)).toBeNull();
    expect(expandableRowOf(stops, 9)).toBeNull();
  });
});
