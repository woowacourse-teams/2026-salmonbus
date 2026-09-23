import type { Board, DirectionInfo, LiveVehicles, StopState, Vehicle } from "@/shared/api/routeForecast.types";

export const upInfo: DirectionInfo = {
  id: "UP",
  name: "안양역 방면",
  originStopName: "도촌동9단지앞",
  terminalStopName: "안양역",
  firstDepartureTime: "04:50",
  lastDepartureTime: "23:30",
};

export const downInfo: DirectionInfo = {
  id: "DOWN",
  name: "도촌동 방면",
  originStopName: "안양역",
  terminalStopName: "도촌동9단지앞",
  firstDepartureTime: "05:00",
  lastDepartureTime: "23:30",
};

export function stopAt(sequence: number, overrides: Partial<StopState> = {}): StopState {
  return {
    sequence,
    stopId: `S${sequence}`,
    name: `정류장${sequence}`,
    direction: sequence <= 3 ? "UP" : "DOWN",
    boardingAllowed: true,
    approachingVehicles: [],
    ...overrides,
  };
}

export function boardWith(overrides: Partial<Board> = {}): Board {
  return {
    route: {
      id: "R1",
      displayName: "3330",
      startStopName: "도촌동9단지앞",
      endStopName: "안양역",
      status: "FORECAST_READY",
      turnSequence: 3,
      referenceVersionId: "v1",
      directions: [upInfo, downInfo],
    },
    observedAt: "2026-09-01T08:12:40+09:00",
    staleAt: "2026-09-01T08:17:40+09:00",
    model: { releaseId: "test", trainedThrough: "2026-08-31" },
    vehiclesInService: 3,
    stops: [1, 2, 3, 4, 5, 6].map((sequence) => stopAt(sequence)),
    ...overrides,
  };
}

export function vehicleAt(currentStopSequence: number, overrides: Partial<Vehicle> = {}): Vehicle {
  return {
    vehicleId: `V${currentStopSequence}`,
    direction: currentStopSequence <= 3 ? "UP" : "DOWN",
    currentStopSequence,
    stopId: `S${currentStopSequence}`,
    stopName: `정류장${currentStopSequence}`,
    phase: "IN_TRANSIT",
    seat: { kind: "EXACT", remaining: 10 },
    ...overrides,
  };
}

export function liveVehiclesWith(vehicles: Vehicle[], overrides: Partial<LiveVehicles> = {}): LiveVehicles {
  return {
    routeId: "R1",
    referenceVersionId: "v1",
    observation: {
      state: "VEHICLES_PRESENT",
      observedAt: "2026-09-01T08:12:31+09:00",
      staleAt: "2026-09-01T08:17:31+09:00",
    },
    vehicles,
    ...overrides,
  };
}
