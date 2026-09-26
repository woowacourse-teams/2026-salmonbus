/* eslint-disable @typescript-eslint/no-unused-vars -- 아래 함수들은 호출용이 아니라 컴파일 검사용이라서 어디서도 쓰지 않는다 */
import type {
  ApproachingVehicle,
  Board,
  BoardRoute,
  ErrorResponse,
  Observation,
  SeatInfo,
  StopState,
  Vehicle,
  VehicleForecast,
} from "./routeForecast.types";

// 아래 함수들은 일부러 틀리게 쓴 코드이다. 타입이 제대로라면 각 줄에서 에러가 나고, @ts-expect-error가 그걸 삼킨다.
// 누가 타입을 느슨하게 고쳐서 에러가 사라지면 @ts-expect-error가 거꾸로 에러를 내니, CI 타입 검사에서 바로 걸린다.

function rejectsSeatCountOnUnknown(): SeatInfo {
  const smuggled = { kind: "UNKNOWN" as const, remaining: 3 };
  // @ts-expect-error 좌석 모름(UNKNOWN)인데 remaining 값을 채우면 막혀야 한다
  const seat: SeatInfo = smuggled;
  return seat;
}

function rejectsUncheckedExpectedSeats(approaching: ApproachingVehicle): number {
  if (approaching.forecast.status !== "AVAILABLE") return 0;
  // @ts-expect-error expectedSeats는 없을 수 있는 필드라, 있는지 확인 없이 바로 쓰면 막혀야 한다
  const seats: number = approaching.forecast.expectedSeats;
  return seats;
}

function rejectsLegacyExpectedSeats(approaching: ApproachingVehicle): unknown {
  // @ts-expect-error 예상 좌석은 차량 최상위가 아니라 forecast 안에 있다
  return approaching.expectedSeats;
}

function rejectsLegacySeatProbability(approaching: ApproachingVehicle): unknown {
  // @ts-expect-error 탑승 확률은 차량 최상위가 아니라 forecast 안에 있다
  return approaching.seatAvailableProbability;
}

function rejectsUncheckedForecastProbability(forecast: VehicleForecast): number {
  // @ts-expect-error UNAVAILABLE에는 확률이 없으므로 status 확인 없이 number로 사용할 수 없다
  const probability: number = forecast.seatAvailableProbability;
  return probability;
}

function rejectsValuesOnUnavailableForecast(): VehicleForecast {
  const smuggled = { status: "UNAVAILABLE" as const, seatAvailableProbability: 0.8, expectedSeats: 5 };
  // @ts-expect-error 예측 불가 응답에는 예측값을 넣을 수 없다
  const forecast: VehicleForecast = smuggled;
  return forecast;
}

function rejectsUncheckedVehicleId(vehicle: Vehicle): string {
  // @ts-expect-error vehicleId는 null일 수 있어서, null 확인 없이 바로 쓰면 막혀야 한다
  return vehicle.vehicleId.toUpperCase();
}

function rejectsUncheckedTurnSequence(route: BoardRoute): number {
  // @ts-expect-error turnSequence도 null일 수 있어서, null 확인 없이 바로 쓰면 막혀야 한다
  const sequence: number = route.turnSequence;
  return sequence;
}

function rejectsUncheckedObservedAt(observation: Observation): string {
  // @ts-expect-error 관측이 없으면 observedAt이 null이라, null 확인 없이 바로 쓰면 막혀야 한다
  return observation.observedAt.slice(0, 10);
}

function rejectsLegacyStationId(stop: StopState): string {
  // @ts-expect-error stationId는 v3 시절 이름이라 쓰면 막혀야 한다 (v4에서 stopId로 바뀜)
  return stop.stationId;
}

function rejectsLegacyForecastMeta(board: Board): unknown {
  // @ts-expect-error forecastMeta는 v3 시절 필드라 쓰면 막혀야 한다 (v4에서 observedAt과 model로 쪼개짐)
  return board.forecastMeta;
}

function rejectsLegacyRetryable(error: ErrorResponse): unknown {
  // @ts-expect-error retryable은 v4-1에서 빠진 필드다 (재시도는 상태 코드와 Retry-After로 판단한다)
  return error.retryable;
}

function rejectsExplicitUndefinedRemaining(): SeatInfo {
  const smuggled = { kind: "UNKNOWN" as const, remaining: undefined };
  // @ts-expect-error exactOptionalPropertyTypes 아래서는 remaining: undefined 주입되는 것도 막혀야 한다
  const seat: SeatInfo = smuggled;
  return seat;
}

// 아래 함수들은 정상 코드다.
function usesBoardStaleAt(board: Board): string {
  return board.staleAt.slice(0, 10);
}

// kind로 분기하면 remaining이 number로 좁혀지는지 확인한다.
function narrowsSeatByKind(seat: SeatInfo): number {
  if (seat.kind === "EXACT") {
    return seat.remaining;
  }
  return 0;
}

function narrowsForecastByStatus(forecast: VehicleForecast): number {
  if (forecast.status === "AVAILABLE") {
    return forecast.seatAvailableProbability;
  }
  return 0;
}
