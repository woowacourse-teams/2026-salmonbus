import type {
  ApproachingVehicle,
  Board,
  ErrorCode,
  ErrorResponse,
  LiveVehicles,
  RouteListResponse,
  StopState,
} from "./routeForecast.types";

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

type MockStop = Omit<StopState, "approachingVehicles">;

// 3330번의 실제 정류장 순서. 회차 지점인 안양역까지가 상행이고 이후가 하행이다.
const route3330Stops = [
  { sequence: 1, stopId: "205000227", name: "도촌동9단지앞", direction: "UP", boardingAllowed: true },
  { sequence: 2, stopId: "205000220", name: "도촌7단지.8단지", direction: "UP", boardingAllowed: true },
  {
    sequence: 3,
    stopId: "205000221",
    name: "도촌초등학교.도촌종합사회복지관",
    direction: "UP",
    boardingAllowed: true,
  },
  {
    sequence: 4,
    stopId: "205000222",
    name: "동분당포레스트.스위첸파티오1단지",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 5, stopId: "205000231", name: "동강프라자앞", direction: "UP", boardingAllowed: true },
  { sequence: 6, stopId: "205000217", name: "도촌1.2단지", direction: "UP", boardingAllowed: true },
  { sequence: 7, stopId: "205000098", name: "분재단지.도촌동1단지", direction: "UP", boardingAllowed: true },
  { sequence: 8, stopId: "205000175", name: "매화마을주공3단지", direction: "UP", boardingAllowed: true },
  { sequence: 9, stopId: "205000210", name: "매화마을3단지", direction: "UP", boardingAllowed: true },
  { sequence: 10, stopId: "206000726", name: "야탑119안전센터", direction: "UP", boardingAllowed: true },
  { sequence: 11, stopId: "206000729", name: "연꽃마을4단지", direction: "UP", boardingAllowed: true },
  { sequence: 12, stopId: "205000173", name: "야탑중학교", direction: "UP", boardingAllowed: true },
  { sequence: 13, stopId: "206000361", name: "동부.코오롱아파트", direction: "UP", boardingAllowed: true },
  {
    sequence: 14,
    stopId: "206000157",
    name: "야탑역.종합버스터미널(전면)",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 15, stopId: "206000392", name: "쌍용아파트", direction: "UP", boardingAllowed: true },
  { sequence: 16, stopId: "206000055", name: "성남아트센터.태원고교", direction: "UP", boardingAllowed: true },
  { sequence: 17, stopId: "206000054", name: "이매역", direction: "UP", boardingAllowed: true },
  {
    sequence: 18,
    stopId: "206000053",
    name: "송림고교.이매촌성지아파트.청구아파트",
    direction: "UP",
    boardingAllowed: true,
  },
  {
    sequence: 19,
    stopId: "206000316",
    name: "이매촌한신.서현역.AK프라자",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 20, stopId: "206000530", name: "성남역.백현마을2단지", direction: "UP", boardingAllowed: true },
  { sequence: 21, stopId: "206000531", name: "백현마을1단지", direction: "UP", boardingAllowed: true },
  {
    sequence: 22,
    stopId: "206000532",
    name: "판교역.낙생육교.현대백화점",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 23, stopId: "277103149", name: "판교TG(경유)", direction: "UP", boardingAllowed: false },
  { sequence: 24, stopId: "277102599", name: "청계터널진입(경유)", direction: "UP", boardingAllowed: false },
  { sequence: 25, stopId: "226000190", name: "의왕청계영업소", direction: "UP", boardingAllowed: true },
  { sequence: 26, stopId: "277103157", name: "학의JC(경유)", direction: "UP", boardingAllowed: false },
  { sequence: 27, stopId: "209000133", name: "농수산물시장", direction: "UP", boardingAllowed: true },
  { sequence: 28, stopId: "209000160", name: "귀인중학교", direction: "UP", boardingAllowed: true },
  { sequence: 29, stopId: "209000057", name: "초원대림아파트", direction: "UP", boardingAllowed: true },
  { sequence: 30, stopId: "209000132", name: "한림대병원후문", direction: "UP", boardingAllowed: true },
  { sequence: 31, stopId: "209000097", name: "안양시청", direction: "UP", boardingAllowed: true },
  { sequence: 32, stopId: "209000131", name: "동안구청", direction: "UP", boardingAllowed: true },
  { sequence: 33, stopId: "209000094", name: "동안경찰서.범계역", direction: "UP", boardingAllowed: true },
  { sequence: 34, stopId: "209000093", name: "안양우편물류센터", direction: "UP", boardingAllowed: true },
  {
    sequence: 35,
    stopId: "209000119",
    name: "효성티앤씨.진영레미콘",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 36, stopId: "208000005", name: "명학대교", direction: "UP", boardingAllowed: true },
  {
    sequence: 37,
    stopId: "208000004",
    name: "성결대학교.안양아트센터.명학역",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 38, stopId: "208000003", name: "만안구청", direction: "UP", boardingAllowed: true },
  {
    sequence: 39,
    stopId: "208000002",
    name: "안양센트럴헤센.KCC스위첸",
    direction: "UP",
    boardingAllowed: true,
  },
  {
    sequence: 40,
    stopId: "208000001",
    name: "서안양우체국.국제나은병원",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 41, stopId: "208000066", name: "남부시장", direction: "UP", boardingAllowed: true },
  {
    sequence: 42,
    stopId: "208000065",
    name: "안양1번가.안양고용센터",
    direction: "UP",
    boardingAllowed: true,
  },
  { sequence: 43, stopId: "208000069", name: "안양역", direction: "UP", boardingAllowed: true },
  { sequence: 44, stopId: "277101770", name: "원평노블레스5차(경유)", direction: "DOWN", boardingAllowed: false },
  {
    sequence: 45,
    stopId: "208000158",
    name: "안양시외버스정류소.교보생명.댕리단길",
    direction: "DOWN",
    boardingAllowed: true,
  },
  {
    sequence: 46,
    stopId: "208000254",
    name: "포스빌.안양고용센터",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 47, stopId: "208000156", name: "벽산상가.2001아울렛", direction: "DOWN", boardingAllowed: true },
  {
    sequence: 48,
    stopId: "208000155",
    name: "서안양우체국.국제나은병원",
    direction: "DOWN",
    boardingAllowed: true,
  },
  {
    sequence: 49,
    stopId: "208000087",
    name: "안양센트럴헤센.KCC스위첸",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 50, stopId: "208000086", name: "만안구청", direction: "DOWN", boardingAllowed: true },
  {
    sequence: 51,
    stopId: "208000281",
    name: "성결대학교.안양아트센터.명학역",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 52, stopId: "208000084", name: "명학대교", direction: "DOWN", boardingAllowed: true },
  {
    sequence: 53,
    stopId: "209000118",
    name: "효성티앤씨.진영레미콘",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 54, stopId: "209000110", name: "안양우편물류센터", direction: "DOWN", boardingAllowed: true },
  { sequence: 55, stopId: "209000135", name: "롯데백화점.범계역", direction: "DOWN", boardingAllowed: true },
  { sequence: 56, stopId: "209000109", name: "문화의거리", direction: "DOWN", boardingAllowed: true },
  { sequence: 57, stopId: "209000108", name: "안양시청", direction: "DOWN", boardingAllowed: true },
  { sequence: 58, stopId: "209000130", name: "중앙공원", direction: "DOWN", boardingAllowed: true },
  { sequence: 59, stopId: "209000072", name: "향촌현대아파트", direction: "DOWN", boardingAllowed: true },
  { sequence: 60, stopId: "209000159", name: "귀인중학교", direction: "DOWN", boardingAllowed: true },
  { sequence: 61, stopId: "209000129", name: "농수산물시장", direction: "DOWN", boardingAllowed: true },
  { sequence: 62, stopId: "277103156", name: "학의JC(경유)", direction: "DOWN", boardingAllowed: false },
  { sequence: 63, stopId: "226000191", name: "의왕청계영업소", direction: "DOWN", boardingAllowed: true },
  { sequence: 64, stopId: "277102796", name: "청계터널출입(경유)", direction: "DOWN", boardingAllowed: false },
  { sequence: 65, stopId: "277103148", name: "판교TG(경유)", direction: "DOWN", boardingAllowed: false },
  {
    sequence: 66,
    stopId: "206000535",
    name: "판교역.낙생육교.현대백화점",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 67, stopId: "206000536", name: "백현마을4단지", direction: "DOWN", boardingAllowed: true },
  {
    sequence: 68,
    stopId: "206000537",
    name: "성남역.백현마을3단지",
    direction: "DOWN",
    boardingAllowed: true,
  },
  {
    sequence: 69,
    stopId: "206000239",
    name: "이매촌한신.서현역.AK프라자",
    direction: "DOWN",
    boardingAllowed: true,
  },
  {
    sequence: 70,
    stopId: "206000028",
    name: "송림고등학교.이매촌청구아파트.동신아파트",
    direction: "DOWN",
    boardingAllowed: true,
  },
  {
    sequence: 71,
    stopId: "206000227",
    name: "이매역.진흥아파트.동신아파트",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 72, stopId: "206000226", name: "성남아트센터.태원고교", direction: "DOWN", boardingAllowed: true },
  { sequence: 73, stopId: "206000441", name: "경남아너스빌", direction: "DOWN", boardingAllowed: true },
  {
    sequence: 74,
    stopId: "206000784",
    name: "야탑역.종합버스터미널(광역버스)",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 75, stopId: "206000057", name: "야탑중학교", direction: "DOWN", boardingAllowed: true },
  { sequence: 76, stopId: "206000728", name: "매화마을1단지", direction: "DOWN", boardingAllowed: true },
  { sequence: 77, stopId: "206000478", name: "매화마을3단지", direction: "DOWN", boardingAllowed: true },
  { sequence: 78, stopId: "206000264", name: "매화마을주공3단지", direction: "DOWN", boardingAllowed: true },
  { sequence: 79, stopId: "205000097", name: "도촌동1단지앞", direction: "DOWN", boardingAllowed: true },
  { sequence: 80, stopId: "205000211", name: "도촌1.2단지", direction: "DOWN", boardingAllowed: true },
  {
    sequence: 81,
    stopId: "205000230",
    name: "도촌2단지.근로복지공단",
    direction: "DOWN",
    boardingAllowed: true,
  },
  {
    sequence: 82,
    stopId: "205000212",
    name: "동분당포레스트.스위첸파티오1단지",
    direction: "DOWN",
    boardingAllowed: true,
  },
  {
    sequence: 83,
    stopId: "205000213",
    name: "도촌초등학교.도촌종합사회복지관",
    direction: "DOWN",
    boardingAllowed: true,
  },
  { sequence: 84, stopId: "205000214", name: "도촌7단지.8단지", direction: "DOWN", boardingAllowed: true },
  { sequence: 85, stopId: "205000226", name: "도촌동9단지앞", direction: "DOWN", boardingAllowed: true },
] satisfies MockStop[];

// 상·하행 첫 구간부터 차량이 보이도록 실제 정류장 위에 네 대씩 분산한 개발용 스냅샷이다.
// 판정보드 예보와 실시간 위치가 서로 다른 차량을 가리키지 않도록 아래 배열을 함께 사용한다.
const liveVehicleFixtures = [
  {
    vehicleId: "V-3330-UP-01",
    direction: "UP",
    currentStopSequence: 2,
    stopId: "205000220",
    stopName: "도촌7단지.8단지",
    phase: "DEPARTED",
    seat: { kind: "EXACT", remaining: 38 },
  },
  {
    vehicleId: "V-3330-UP-02",
    direction: "UP",
    currentStopSequence: 12,
    stopId: "205000173",
    stopName: "야탑중학교",
    phase: "IN_TRANSIT",
    seat: { kind: "EXACT", remaining: 24 },
  },
  {
    vehicleId: "V-3330-UP-02B",
    direction: "UP",
    currentStopSequence: 12,
    stopId: "205000173",
    stopName: "야탑중학교",
    phase: "IN_TRANSIT",
    seat: { kind: "EXACT", remaining: 6 },
  },
  {
    vehicleId: "V-3330-UP-03",
    direction: "UP",
    currentStopSequence: 22,
    stopId: "206000532",
    stopName: "판교역.낙생육교.현대백화점",
    phase: "ARRIVING",
    seat: { kind: "EXACT", remaining: 11 },
  },
  {
    vehicleId: "V-3330-UP-04",
    direction: "UP",
    currentStopSequence: 33,
    stopId: "209000094",
    stopName: "동안경찰서.범계역",
    phase: "DEPARTED",
    seat: { kind: "EXACT", remaining: 0 },
  },
  {
    vehicleId: "V-3330-DOWN-01",
    direction: "DOWN",
    currentStopSequence: 45,
    stopId: "208000158",
    stopName: "안양시외버스정류소.교보생명.댕리단길",
    phase: "IN_TRANSIT",
    seat: { kind: "EXACT", remaining: 40 },
  },
  {
    vehicleId: "V-3330-DOWN-02",
    direction: "DOWN",
    currentStopSequence: 56,
    stopId: "209000109",
    stopName: "문화의거리",
    phase: "DEPARTED",
    seat: { kind: "EXACT", remaining: 27 },
  },
  {
    vehicleId: "V-3330-DOWN-03",
    direction: "DOWN",
    currentStopSequence: 68,
    stopId: "206000537",
    stopName: "성남역.백현마을3단지",
    phase: "ARRIVING",
    seat: { kind: "EXACT", remaining: 13 },
  },
  {
    vehicleId: "V-3330-DOWN-04",
    direction: "DOWN",
    currentStopSequence: 79,
    stopId: "205000097",
    stopName: "도촌동1단지앞",
    phase: "DEPARTED",
    seat: { kind: "UNKNOWN" },
  },
] satisfies LiveVehicles["vehicles"];

const MAX_MOCK_FORECAST_HORIZON = 12;

function approachingVehiclesAt(stop: MockStop): ApproachingVehicle[] {
  if (!stop.boardingAllowed) return [];

  return liveVehicleFixtures
    .flatMap((vehicle): ApproachingVehicle[] => {
      if (vehicle.direction !== stop.direction) return [];

      const horizonStops = stop.sequence - vehicle.currentStopSequence;
      if (horizonStops <= 0 || horizonStops > MAX_MOCK_FORECAST_HORIZON) return [];

      if (vehicle.seat.kind === "UNKNOWN") {
        return [
          {
            vehicleId: vehicle.vehicleId,
            horizonStops,
            seatAvailableProbability: 0.5,
          },
        ];
      }

      const expectedSeats = Math.max(0, vehicle.seat.remaining - Math.ceil(horizonStops / 2));
      return [
        {
          vehicleId: vehicle.vehicleId,
          horizonStops,
          seatAvailableProbability: seatProbabilityFor(expectedSeats),
          expectedSeats,
        },
      ];
    })
    .sort((left, right) => left.horizonStops - right.horizonStops)
    .slice(0, 3);
}

function seatProbabilityFor(expectedSeats: number): number {
  if (expectedSeats >= 25) return 0.92;
  if (expectedSeats >= 10) return 0.68;
  if (expectedSeats >= 4) return 0.42;
  return 0.14;
}

export const boardMock = {
  route: {
    id: "204000057",
    displayName: "3330",
    startStopName: "도촌동9단지앞",
    endStopName: "안양역",
    status: "FORECAST_READY",
    turnSequence: 43,
    referenceVersionId: "1",
    directions: [
      {
        id: "UP",
        name: "안양역 방면",
        originStopName: "도촌동9단지앞",
        terminalStopName: "안양역",
        firstDepartureTime: "04:50",
        lastDepartureTime: "23:30",
      },
      {
        id: "DOWN",
        name: "도촌동9단지앞 방면",
        originStopName: "안양역",
        terminalStopName: "도촌동9단지앞",
        firstDepartureTime: "05:00",
        lastDepartureTime: "23:30",
      },
    ],
  },
  observedAt: "2026-09-03T08:12:40+09:00",
  staleAt: "2026-09-03T08:17:40+09:00",
  model: {
    releaseId: "salmonbus-mock-3330",
    trainedThrough: "2026-08-23T23:59:55+09:00",
  },
  vehiclesInService: 9,
  stops: route3330Stops.map((stop) => ({
    ...stop,
    approachingVehicles: approachingVehiclesAt(stop),
  })),
} satisfies Board;

export const boardClosedMock = {
  route: boardMock.route,
  observedAt: "2026-08-18T23:58:20+09:00",
  staleAt: "2026-08-19T00:03:20+09:00",
  model: boardMock.model,
  vehiclesInService: 0,
  stops: route3330Stops.map((stop) => ({ ...stop, approachingVehicles: [] })),
} satisfies Board;

export const boardQuietMock = {
  route: boardMock.route,
  observedAt: "2026-08-18T05:03:11+09:00",
  staleAt: "2026-08-18T05:08:11+09:00",
  model: boardMock.model,
  vehiclesInService: 1,
  stops: route3330Stops.map((stop) => ({ ...stop, approachingVehicles: [] })),
} satisfies Board;

export const liveVehiclesMock = {
  routeId: "204000057",
  referenceVersionId: "1",
  observation: {
    state: "VEHICLES_PRESENT",
    observedAt: "2026-09-03T08:12:31+09:00",
    staleAt: "2026-09-03T08:17:31+09:00",
  },
  vehicles: liveVehicleFixtures,
} satisfies LiveVehicles;

export const liveVehiclesEmptyMock = {
  routeId: "204000057",
  referenceVersionId: "1",
  observation: {
    state: "NO_VEHICLES_OBSERVED",
    observedAt: "2026-08-18T23:59:02+09:00",
    staleAt: "2026-08-19T00:04:02+09:00",
  },
  vehicles: [],
} satisfies LiveVehicles;

export const liveVehiclesUnknownMock = {
  routeId: "204000057",
  referenceVersionId: "1",
  observation: {
    state: "UNKNOWN",
    observedAt: null,
    staleAt: null,
  },
  vehicles: [],
} satisfies LiveVehicles;

export const liveVehiclesRevisedMock = {
  routeId: "204000057",
  referenceVersionId: "2",
  observation: {
    state: "VEHICLES_PRESENT",
    observedAt: "2026-08-25T09:40:12+09:00",
    staleAt: "2026-08-25T09:45:12+09:00",
  },
  vehicles: [
    {
      vehicleId: "V-3330-UP-05",
      direction: "UP",
      currentStopSequence: 8,
      stopId: "205000175",
      stopName: "매화마을주공3단지",
      phase: "IN_TRANSIT",
      seat: { kind: "EXACT", remaining: 27 },
    },
  ],
} satisfies LiveVehicles;

export const errorResponseMocks = {
  INVALID_ROUTE_ID: {
    code: "INVALID_ROUTE_ID",
    message: "routeId는 9자리 숫자여야 합니다.",
    requestId: "req-01J9X2ABCD",
  },
  ROUTE_NOT_FOUND: {
    code: "ROUTE_NOT_FOUND",
    message: "등록되지 않은 노선입니다.",
    requestId: "req-01J9X2ABCE",
  },
  MODEL_OUT_OF_SCOPE: {
    code: "MODEL_OUT_OF_SCOPE",
    message: "활성 모델 번들이 지원하지 않는 노선 판본입니다.",
    requestId: "req-01J9X2ABCG",
  },
  NO_RECENT_OBSERVATION: {
    code: "NO_RECENT_OBSERVATION",
    message: "no vehicle observation recent enough to anchor a forecast",
    requestId: "req-01J9X2ABCF",
  },
  SERVICE_UNAVAILABLE: {
    code: "SERVICE_UNAVAILABLE",
    message: "일시적인 서버 장애가 발생했습니다.",
    requestId: "req-01J9X2ABCH",
  },
  INVALID_REQUEST: {
    code: "INVALID_REQUEST",
    message: "요청 형식이 올바르지 않습니다.",
    requestId: "req-01J9X2ABCI",
  },
  ENDPOINT_NOT_FOUND: {
    code: "ENDPOINT_NOT_FOUND",
    message: "요청한 경로를 찾을 수 없습니다.",
    requestId: "req-01J9X2ABCI",
  },
  METHOD_NOT_ALLOWED: {
    code: "METHOD_NOT_ALLOWED",
    message: "이 경로에서 지원하지 않는 요청 방식입니다.",
    requestId: "req-01J9X2ABCK",
  },
  INTERNAL_ERROR: {
    code: "INTERNAL_ERROR",
    message: "요청을 처리하지 못했습니다.",
    requestId: "req-01J9X2ABCK",
  },
} satisfies Record<ErrorCode, ErrorResponse>;
