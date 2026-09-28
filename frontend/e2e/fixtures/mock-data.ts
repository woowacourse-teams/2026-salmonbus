import type { Board, Direction, LiveVehicles, RouteListResponse } from "../../src/shared/api/routeForecast.types";

export const observedAt = "2026-09-16T08:00:00+09:00";
export const staleAt = "2026-09-16T08:05:00+09:00";
export const pollIntervalMs = 60_000;
export const routeId = "200000001";
export const apiPaths = {
  routes: "/api/v1/routes",
  board: `/api/v1/routes/${routeId}/board`,
  vehicles: `/api/v1/routes/${routeId}/vehicles`,
};

export const directionLabels = {
  UP: "테스트 기점 → 테스트 종점",
  DOWN: "테스트 종점 → 테스트 기점",
};

export const targetStopNames = { UP: "상행 중앙역", DOWN: "하행 중앙역" };

export const stopNames: Record<Direction, string[]> = {
  UP: [
    "테스트 기점",
    "상행 정류장 2",
    "상행 정류장 3",
    "상행 정류장 4",
    "상행 정류장 5",
    "상행 정류장 6",
    "상행 정류장 7",
    "상행 정류장 8",
    "상행 정류장 9",
    "상행 정류장 10",
    "상행 중앙역",
    "테스트 종점",
  ],
  DOWN: [
    "테스트 종점",
    "하행 정류장 2",
    "하행 정류장 3",
    "하행 정류장 4",
    "하행 정류장 5",
    "하행 정류장 6",
    "하행 정류장 7",
    "하행 정류장 8",
    "하행 정류장 9",
    "하행 정류장 10",
    "하행 중앙역",
    "테스트 기점",
  ],
};

// 각 테스트가 별도의 응답 객체를 받아 병렬 실행 중에도 서로 영향을 주지 않는다.
export function createApiData(): { routes: RouteListResponse; board: Board; vehicles: LiveVehicles } {
  const route = {
    id: routeId,
    displayName: "3330",
    startStopName: "테스트 기점",
    endStopName: "테스트 종점",
    status: "FORECAST_READY",
  } satisfies RouteListResponse["routes"][number];

  const board: Board = {
    route: {
      ...route,
      referenceVersionId: "1",
      turnSequence: 12,
      directions: [
        {
          id: "UP",
          name: "테스트 종점 방면",
          originStopName: "테스트 기점",
          terminalStopName: "테스트 종점",
          firstDepartureTime: "05:00",
          lastDepartureTime: "23:00",
        },
        {
          id: "DOWN",
          name: "테스트 기점 방면",
          originStopName: "테스트 종점",
          terminalStopName: "테스트 기점",
          firstDepartureTime: "05:00",
          lastDepartureTime: "23:00",
        },
      ],
    },
    observedAt,
    staleAt,
    model: { releaseId: "e2e-model", trainedThrough: "2026-09-15" },
    vehiclesInService: 3,
    stops: (["UP", "DOWN"] as const).flatMap((direction) =>
      stopNames[direction].map((name, index) => ({
        sequence: (direction === "UP" ? 0 : 12) + index + 1,
        stopId: `${direction === "UP" ? "100" : "200"}${index + 1}`,
        name,
        direction,
        boardingAllowed: true,
        // 두 방향 모두 목표 정류장이 첫 화면 아래에 위치한다.
        approachingVehicles:
          index !== 10
            ? []
            : direction === "UP"
              ? [
                  // 정렬도 검증하도록 먼 버스를 먼저 응답한다.
                  {
                    vehicleId: "bus-2",
                    horizonStops: 7,
                    forecast: { status: "AVAILABLE", seatAvailableProbability: 0.5, expectedSeats: 2 },
                  },
                  {
                    vehicleId: "bus-1",
                    horizonStops: 3,
                    forecast: { status: "AVAILABLE", seatAvailableProbability: 0.8, expectedSeats: 5 },
                  },
                ]
              : [
                  {
                    vehicleId: "bus-3",
                    horizonStops: 2,
                    forecast: { status: "AVAILABLE", seatAvailableProbability: 0.9, expectedSeats: 8 },
                  },
                ],
      })),
    ),
  };

  return {
    routes: { routes: [route] },
    board,
    vehicles: {
      routeId,
      referenceVersionId: board.route.referenceVersionId,
      observation: { state: "VEHICLES_PRESENT", observedAt, staleAt },
      vehicles: [
        {
          vehicleId: "bus-1",
          direction: "UP",
          currentStopSequence: 8,
          stopId: "1008",
          stopName: "상행 정류장 8",
          phase: "ARRIVING",
          seat: { kind: "EXACT", remaining: 5 },
        },
        {
          vehicleId: "bus-2",
          direction: "UP",
          currentStopSequence: 4,
          stopId: "1004",
          stopName: "상행 정류장 4",
          phase: "ARRIVING",
          seat: { kind: "EXACT", remaining: 2 },
        },
        {
          vehicleId: "bus-3",
          direction: "DOWN",
          currentStopSequence: 21,
          stopId: "2009",
          stopName: "하행 정류장 9",
          phase: "ARRIVING",
          seat: { kind: "EXACT", remaining: 8 },
        },
      ],
    },
  };
}
