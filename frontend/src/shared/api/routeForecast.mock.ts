import type { Board, ErrorCode, ErrorResponse, LiveVehicles, RouteListResponse } from "./routeForecast.types";

export const routeListMock = {
  routes: [
    {
      id: "204000057",
      displayName: "3330",
      startStopName: "도촌동9단지앞",
      endStopName: "안양역",
      status: "FORECAST_READY",
    },
    {
      id: "234000050",
      displayName: "1650",
      startStopName: "구리수택차고지",
      endStopName: "안양역",
      status: "PREPARING",
    },
  ],
} satisfies RouteListResponse;

export const boardMock = {
  route: {
    id: "204000057",
    displayName: "3330",
    startStopName: "도촌동9단지앞",
    endStopName: "안양역",
    status: "FORECAST_READY",
    turnSequence: 43,
    referenceVersionId: "204000057-20260818",
    directions: [
      {
        id: "UP",
        name: "안양 방면",
        originStopName: "도촌동9단지앞",
        terminalStopName: "안양역",
        firstDepartureTime: "04:50",
        lastDepartureTime: "23:00",
      },
      {
        id: "DOWN",
        name: "도촌 방면",
        originStopName: "안양역",
        terminalStopName: "도촌동9단지앞",
        firstDepartureTime: "05:40",
        lastDepartureTime: "23:55",
      },
    ],
  },
  observedAt: "2026-08-18T08:12:40+09:00",
  model: {
    releaseId: "A18",
    trainedThrough: "2026-08-18",
  },
  vehiclesInService: 5,
  stops: [
    {
      sequence: 5,
      stopId: "224000059",
      name: "도촌동주공1단지",
      direction: "UP",
      boardingAllowed: true,
      approachingVehicles: [],
    },
    {
      sequence: 18,
      stopId: "224000132",
      name: "야탑역",
      direction: "UP",
      boardingAllowed: true,
      approachingVehicles: [
        {
          vehicleId: "V-3330-01",
          horizonStops: 3,
          seatAvailableProbability: 0.82,
          expectedSeats: 21,
        },
        {
          vehicleId: null,
          horizonStops: 11,
          seatAvailableProbability: 0.44,
        },
      ],
    },
    {
      sequence: 30,
      stopId: "277101995",
      name: "판교테크노밸리(경유)",
      direction: "UP",
      boardingAllowed: false,
      approachingVehicles: [],
    },
    {
      sequence: 44,
      stopId: "233001855",
      name: "인덕원역",
      direction: "DOWN",
      boardingAllowed: true,
      approachingVehicles: [
        {
          vehicleId: "V-3330-02",
          horizonStops: 1,
          seatAvailableProbability: 0.91,
          expectedSeats: 30,
        },
        {
          vehicleId: "V-3330-03",
          horizonStops: 6,
          seatAvailableProbability: 0.55,
          expectedSeats: 9,
        },
        {
          vehicleId: "V-3330-04",
          horizonStops: 12,
          seatAvailableProbability: 0.18,
        },
      ],
    },
    {
      sequence: 52,
      stopId: "233002110",
      name: "범계역",
      direction: "DOWN",
      boardingAllowed: true,
      approachingVehicles: [],
    },
  ],
} satisfies Board;

export const boardClosedMock = {
  route: boardMock.route,
  observedAt: "2026-08-18T23:58:20+09:00",
  model: boardMock.model,
  vehiclesInService: 0,
  stops: [
    {
      sequence: 18,
      stopId: "224000132",
      name: "야탑역",
      direction: "UP",
      boardingAllowed: true,
      approachingVehicles: [],
    },
    {
      sequence: 44,
      stopId: "233001855",
      name: "인덕원역",
      direction: "DOWN",
      boardingAllowed: true,
      approachingVehicles: [],
    },
  ],
} satisfies Board;

export const boardQuietMock = {
  route: boardMock.route,
  observedAt: "2026-08-18T05:03:11+09:00",
  model: boardMock.model,
  vehiclesInService: 1,
  stops: [
    {
      sequence: 18,
      stopId: "224000132",
      name: "야탑역",
      direction: "UP",
      boardingAllowed: true,
      approachingVehicles: [],
    },
    {
      sequence: 44,
      stopId: "233001855",
      name: "인덕원역",
      direction: "DOWN",
      boardingAllowed: true,
      approachingVehicles: [],
    },
  ],
} satisfies Board;

export const liveVehiclesMock = {
  routeId: "234000050",
  referenceVersionId: "234000050-20260818",
  observation: {
    state: "VEHICLES_PRESENT",
    observedAt: "2026-08-18T08:12:31+09:00",
    staleAt: "2026-08-18T08:13:31+09:00",
  },
  vehicles: [
    {
      vehicleId: "V-1650-07",
      direction: "UP",
      currentStopSequence: 21,
      stopId: "228000601",
      stopName: "구리역",
      phase: "DEPARTED",
      seat: { kind: "EXACT", remaining: 12 },
    },
    {
      vehicleId: "V-1650-11",
      direction: "DOWN",
      currentStopSequence: 58,
      stopId: "233001855",
      stopName: "인덕원역",
      phase: "ARRIVING",
      seat: { kind: "EXACT", remaining: 0 },
    },
    {
      vehicleId: null,
      direction: "UP",
      currentStopSequence: 35,
      stopId: "228000733",
      stopName: "장자못사거리",
      phase: "IN_TRANSIT",
      seat: { kind: "UNKNOWN" },
    },
  ],
} satisfies LiveVehicles;

export const liveVehiclesEmptyMock = {
  routeId: "234000050",
  referenceVersionId: "234000050-20260818",
  observation: {
    state: "NO_VEHICLES_OBSERVED",
    observedAt: "2026-08-18T23:59:02+09:00",
    staleAt: "2026-08-19T00:09:02+09:00",
  },
  vehicles: [],
} satisfies LiveVehicles;

export const liveVehiclesUnknownMock = {
  routeId: "234000050",
  referenceVersionId: "234000050-20260818",
  observation: {
    state: "UNKNOWN",
    observedAt: null,
    staleAt: null,
  },
  vehicles: [],
} satisfies LiveVehicles;

export const liveVehiclesRevisedMock = {
  routeId: "234000050",
  referenceVersionId: "234000050-20260825",
  observation: {
    state: "VEHICLES_PRESENT",
    observedAt: "2026-08-25T09:40:12+09:00",
    staleAt: "2026-08-25T09:41:12+09:00",
  },
  vehicles: [
    {
      vehicleId: "V-1650-02",
      direction: "UP",
      currentStopSequence: 8,
      stopId: "228000544",
      stopName: "수택고개",
      phase: "IN_TRANSIT",
      seat: { kind: "EXACT", remaining: 27 },
    },
  ],
} satisfies LiveVehicles;

export const errorResponseMocks = {
  INVALID_ROUTE_ID: {
    code: "INVALID_ROUTE_ID",
    message: "routeId must be 9 digits",
    requestId: "req-01J9X2ABCD",
  },
  ROUTE_NOT_FOUND: {
    code: "ROUTE_NOT_FOUND",
    message: "unknown routeId",
    requestId: "req-01J9X2ABCE",
  },
  MODEL_OUT_OF_SCOPE: {
    code: "MODEL_OUT_OF_SCOPE",
    message: "active bundle does not support this route reference",
    requestId: "req-01J9X2ABCG",
  },
  NO_RECENT_OBSERVATION: {
    code: "NO_RECENT_OBSERVATION",
    message: "no vehicle observation recent enough to anchor a forecast",
    requestId: "req-01J9X2ABCF",
  },
  SERVICE_UNAVAILABLE: {
    code: "SERVICE_UNAVAILABLE",
    message: "temporary failure",
    requestId: "req-01J9X2ABCH",
  },
  INVALID_REQUEST: {
    code: "INVALID_REQUEST",
    message: "invalid request",
    requestId: "req-01J9X2ABCL",
  },
  ENDPOINT_NOT_FOUND: {
    code: "ENDPOINT_NOT_FOUND",
    message: "요청한 경로를 찾을 수 없습니다.",
    requestId: "req-01J9X2ABCI",
  },
  METHOD_NOT_ALLOWED: {
    code: "METHOD_NOT_ALLOWED",
    message: "이 경로에서 지원하지 않는 요청 방식입니다.",
    requestId: "req-01J9X2ABCJ",
  },
  INTERNAL_ERROR: {
    code: "INTERNAL_ERROR",
    message: "요청을 처리하지 못했습니다.",
    requestId: "req-01J9X2ABCK",
  },
} satisfies Record<ErrorCode, ErrorResponse>;
