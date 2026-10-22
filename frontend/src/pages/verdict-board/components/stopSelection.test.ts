import { describe, expect, it } from "@jest/globals";
import { stopSelectionFrom } from "./stopSelection";
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
