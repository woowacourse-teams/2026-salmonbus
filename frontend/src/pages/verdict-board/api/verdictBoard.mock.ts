import type { Board, Direction, StopState } from "./verdictBoard.types";

function boardingStop(sequence: number, name: string, direction: Direction, probability: number): StopState {
  return {
    sequence,
    name,
    direction,
    seatForecast: { kind: "ESTIMATED", seatAvailableProbability: probability },
  };
}

function passThroughStop(sequence: number, name: string, direction: Direction): StopState {
  return {
    sequence,
    name,
    direction,
    seatForecast: { kind: "NOT_APPLICABLE" },
  };
}

export const boardMock: Board = {
  route: {
    displayName: "3330",
    directions: [
      { id: "UP", name: "안양역 방면" },
      { id: "DOWN", name: "구리수택차고지 방면" },
    ],
  },
  stops: [
    boardingStop(1, "구리수택차고지", "UP", 0.95),
    boardingStop(2, "수택고교.SK신일.우남아파트", "UP", 0.7),
    passThroughStop(3, "토평삼거리", "UP"),
    boardingStop(4, "테크노마트앞.강변역(C)", "UP", 0.69),
    boardingStop(5, "잠실역.잠실대교남단(중)", "UP", 0.3),
    boardingStop(6, "부흥고등학교", "UP", 0.29),
    boardingStop(7, "범계역", "UP", 0.05),
    boardingStop(8, "안양역", "UP", 0.5),
    boardingStop(9, "안양역", "DOWN", 0.9),
    boardingStop(10, "범계역", "DOWN", 0.4),
    passThroughStop(11, "인덕원역", "DOWN"),
    boardingStop(12, "잠실역.잠실대교남단(중)", "DOWN", 0.15),
    boardingStop(13, "테크노마트앞.강변역(C)", "DOWN", 0.75),
    boardingStop(14, "구리수택차고지", "DOWN", 0.55),
  ],
};
