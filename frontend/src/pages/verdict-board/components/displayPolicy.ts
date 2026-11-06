import type { ApiFailure } from "@/shared/api/client";
import type {
  Board,
  Direction,
  DirectionInfo,
  HeadwayRange,
  StopState,
  Vehicle,
} from "@/shared/api/routeForecast.types";
import { isPositiveInteger, isRecord } from "@/shared/utils/valueGuards";
import {
  arrivalViewsFor,
  representativeArrival,
  arrivalToneFor,
  type ArrivalView,
  type ArrivalTone,
} from "./arrivalPolicy";

export type ServiceState = "running" | "noVehiclesInService" | "outOfService";

export type BoardScreen =
  | { kind: "loading" }
  | { kind: "forecast"; board: Board; direction: Direction }
  | { kind: "outOfService"; board: Board }
  | { kind: "error"; failure: ApiFailure };

export type BoardFreshness = "fresh" | "refreshFailed" | "expired";

type ServicePhase = "withinTimetable" | "outsideTimetable" | "undetermined";

interface StopViewBase {
  sequence: number;
  stopId: string;
  name: string;
  isTurnaround: boolean;
}

export interface BoardingStopView extends StopViewBase {
  kind: "boarding";
  tone: ArrivalTone;
  arrivals: [ArrivalView, ...ArrivalView[]];
}

export interface PassThroughStopView extends StopViewBase {
  kind: "passThrough";
}

export interface NoVehicleStopView extends StopViewBase {
  kind: "noVehicle";
}

export interface CheckingStopView extends StopViewBase {
  kind: "checking";
}

export type SourceStopView = BoardingStopView | PassThroughStopView | NoVehicleStopView;
export type StopView = SourceStopView | CheckingStopView;
export type ExpandableStopView = BoardingStopView | NoVehicleStopView | CheckingStopView;

export interface DirectionView {
  id: Direction;
  label: string;
}

// 관측 시각은 계약상 KST(+09:00)로 계산하여 보여준다.
const KST_LOCAL_TIME = /T(\d{2}):(\d{2})[^Z]*\+09:00$/;
const CLOCK_TIME = /^(\d{1,2}):(\d{2})$/;
const KST_OFFSET_MS = 9 * 60 * 60 * 1000;

export function headwayLabelFor(headways: Board["route"]["headways"], now: number): string {
  const day = new Date(now + KST_OFFSET_MS).getUTCDay();
  const range = headwayRangeFrom(headways?.[day === 0 ? "sunday" : day === 6 ? "saturday" : "weekday"]);
  return range === null ? "정보 준비 중" : `${range.minMinutes}~${range.maxMinutes}분`;
}

function headwayRangeFrom(value: unknown): HeadwayRange | null {
  if (
    !isRecord(value) ||
    !isPositiveInteger(value.minMinutes) ||
    !isPositiveInteger(value.maxMinutes) ||
    value.minMinutes > value.maxMinutes ||
    value.maxMinutes > 1440
  ) {
    return null;
  }
  return { minMinutes: value.minMinutes, maxMinutes: value.maxMinutes };
}

export function serviceStateFor(
  board: Board,
  directionInfo: DirectionInfo,
  vehicles: readonly Vehicle[],
): ServiceState {
  // 시간표의 막차 시각은 기점 출발 시각이라 그 뒤에도 막차는 종점까지 달린다.
  if (servicePhaseOf(directionInfo, board.observedAt) === "outsideTimetable") {
    return vehicles.some((vehicle) => vehicle.direction === directionInfo.id) ? "running" : "outOfService";
  }
  // 실제 운영 시간이라고 하더라도 운행 중인 차량이 하나도 없으면 예측 화면을 보여주지 않는다.
  // 관측한 값일 뿐 계산된 판정이 아니라서, 실제 운영 중 시간대에서만 이 값을 보여준다.
  // 시간표를 못 읽어 판정하지 못한 경우도 예보가 틀린 건 아니므로 판정화면을 보여주되 같은 기준으로 판단한다.
  return board.vehiclesInService > 0 ? "running" : "noVehiclesInService";
}

export type BoardNotice =
  | { kind: "noVehiclesInService" }
  | { kind: "expired"; observedClock: string | null }
  | { kind: "refreshFailed"; observedClock: string | null };

export function boardFreshnessOf(status: string, expired: boolean): BoardFreshness {
  if (expired) return "expired";
  if (status === "refetchError") return "refreshFailed";
  return "fresh";
}

export function observedClockOf(observedAt: string): string | null {
  const match = KST_LOCAL_TIME.exec(observedAt);
  if (match === null) return null;
  const [, hours, minutes] = match;
  if (hours === undefined || minutes === undefined) return null;
  return `${hours}:${minutes}`;
}

export function boardNoticeFor(
  serviceState: ServiceState,
  freshness: BoardFreshness,
  observedAt: string,
): BoardNotice | null {
  const observedClock = observedClockOf(observedAt);
  if (freshness === "expired") return { kind: "expired", observedClock };
  if (freshness === "refreshFailed") return { kind: "refreshFailed", observedClock };
  return serviceState === "noVehiclesInService" ? { kind: "noVehiclesInService" } : null;
}

export function directionInfoFor(board: Board, preferred: Direction | null): DirectionInfo {
  const { directions } = board.route;
  return directions.find((directionInfo) => directionInfo.id === preferred) ?? directions[0];
}

export function stopViewsFor(board: Board, direction: Direction): SourceStopView[] {
  const { turnSequence } = board.route;

  return board.stops
    .filter((stop) => stop.direction === direction)
    .sort((left, right) => left.sequence - right.sequence)
    .map((stop) => toStopView(stop, turnSequence));
}

export function displayedStopViewsFor(board: Board, direction: Direction, freshness: BoardFreshness): StopView[] {
  const source = stopViewsFor(board, direction);
  if (freshness !== "expired") return source;
  return source.map((stop) =>
    stop.kind === "passThrough"
      ? stop
      : {
          kind: "checking" as const,
          sequence: stop.sequence,
          stopId: stop.stopId,
          name: stop.name,
          isTurnaround: stop.isTurnaround,
        },
  );
}

export function directionViewsFor(board: Board): DirectionView[] {
  return board.route.directions.map((directionInfo) => ({
    id: directionInfo.id,
    label: `${directionInfo.terminalStopName} 방면`,
  }));
}

function toStopView(stop: StopState, turnSequence: number | null): SourceStopView {
  const { sequence, stopId, name } = stop;
  const isTurnaround = touchesTurnaround(sequence, turnSequence);

  if (!stop.boardingAllowed) {
    return { kind: "passThrough", sequence, stopId, name, isTurnaround };
  }

  const [first, ...rest] = arrivalViewsFor(stop.approachingVehicles);
  if (first === undefined) {
    return { kind: "noVehicle", sequence, stopId, name, isTurnaround };
  }

  const arrivals: BoardingStopView["arrivals"] = [first, ...rest];
  return {
    kind: "boarding",
    sequence,
    stopId,
    name,
    isTurnaround,
    tone: arrivalToneFor(representativeArrival(arrivals)),
    arrivals,
  };
}

// 회차 정류장은 상행의 마지막 순번이고, 하행은 그 다음 순번에서 시작한다. 양쪽에 표시한다.
function touchesTurnaround(sequence: number, turnSequence: number | null): boolean {
  if (turnSequence === null) return false;
  return sequence === turnSequence || sequence === turnSequence + 1;
}

function servicePhaseOf(directionInfo: DirectionInfo, observedAt: string): ServicePhase {
  const observed = minutesOf(KST_LOCAL_TIME.exec(observedAt));
  const first = minutesOf(CLOCK_TIME.exec(directionInfo.firstDepartureTime));
  const last = minutesOf(CLOCK_TIME.exec(directionInfo.lastDepartureTime));
  if (observed === null || first === null || last === null) return "undetermined";

  if (first > last) {
    return observed >= first || observed <= last ? "withinTimetable" : "outsideTimetable";
  }
  return first <= observed && observed <= last ? "withinTimetable" : "outsideTimetable";
}

function minutesOf(parsed: RegExpExecArray | null): number | null {
  if (parsed === null) return null;
  const [, hours, minutes] = parsed;
  if (hours === undefined || minutes === undefined) return null;
  return Number(hours) * 60 + Number(minutes);
}
