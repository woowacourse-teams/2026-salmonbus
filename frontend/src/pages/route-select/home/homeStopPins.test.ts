import { describe, expect, it } from "@jest/globals";
import { homeStopPinsFor, type HomeStopScreen } from "./homeStopPins";

interface Stop {
  id: string;
  x: number;
  y: number;
}

const screenAt = (zoom: number): HomeStopScreen => ({
  zoom,
  left: 0,
  top: 0,
  width: 400,
  height: 800,
  pinClearance: 20,
});

function pinsFor(stops: Stop[], zoom: number) {
  return homeStopPinsFor(stops, (stop) => stop, screenAt(zoom)).map(({ stop, shift }) => [stop.id, shift]);
}

describe("homeStopPinsFor", () => {
  it("줌 13보다 작으면 핀을 두지 않는다", () => {
    const stops = [{ id: "a", x: 100, y: 100 }];

    expect(pinsFor(stops, 12)).toEqual([]);
    expect(pinsFor(stops, 13)).toEqual([["a", 0]]);
  });

  it("줌 15보다 작으면 36px 안에 먼저 놓인 핀이 있는 정류장을 뺀다", () => {
    const stops = [
      { id: "first", x: 100, y: 100 },
      { id: "near", x: 120, y: 100 },
      { id: "far", x: 136, y: 100 },
    ];

    expect(pinsFor(stops, 14)).toEqual([
      ["first", 0],
      ["far", 0],
    ]);
  });

  it("뺀 정류장은 다음 정류장과의 간격을 잴 때 세지 않는다", () => {
    const stops = [
      { id: "first", x: 100, y: 100 },
      { id: "dropped", x: 130, y: 100 },
      { id: "next", x: 160, y: 100 },
    ];

    expect(pinsFor(stops, 14)).toEqual([
      ["first", 0],
      ["next", 0],
    ]);
  });

  it("줌 15부터는 가까운 정류장을 빼지 않고 겹치는 핀을 오른쪽으로 핀 하나만큼 민다", () => {
    const stops = [
      { id: "first", x: 100, y: 100 },
      { id: "same", x: 100, y: 100 },
    ];

    expect(pinsFor(stops, 15)).toEqual([
      ["first", 0],
      ["same", 20],
    ]);
  });

  it("오른쪽 자리도 차 있으면 왼쪽으로 밀고, 양쪽이 다 차 있으면 제자리에 둔다", () => {
    const stops = ["first", "right", "left", "stacked"].map((id) => ({ id, x: 100, y: 100 }));

    expect(pinsFor(stops, 15)).toEqual([
      ["first", 0],
      ["right", 20],
      ["left", -20],
      ["stacked", 0],
    ]);
  });

  it("세로로 핀 하나만큼 떨어진 정류장은 밀지 않는다", () => {
    const stops = [
      { id: "upper", x: 100, y: 100 },
      { id: "lower", x: 100, y: 120 },
    ];

    expect(pinsFor(stops, 15)).toEqual([
      ["upper", 0],
      ["lower", 0],
    ]);
  });

  it("화면 가장자리에서 16px 넘게 벗어난 정류장은 뺀다", () => {
    const stops = [
      { id: "inside margin", x: -16, y: 816 },
      { id: "left", x: -17, y: 100 },
      { id: "right", x: 417, y: 100 },
      { id: "above", x: 100, y: -17 },
      { id: "below", x: 100, y: 817 },
    ];

    expect(pinsFor(stops, 15)).toEqual([["inside margin", 0]]);
  });
});
