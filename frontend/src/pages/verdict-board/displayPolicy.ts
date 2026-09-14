import type { Board, Direction, DirectionInfo, StopState } from "@/shared/api/routeForecast.types";
import { arrivalViewsFor, representativeArrival, type ArrivalView } from "./arrivalPolicy";
import type { SeatLevel } from "./seatGrade";

export type ServiceState = "running" | "outOfService";

type ServicePhase = "before" | "running" | "ended" | "undetermined";

interface StopViewBase {
  sequence: number;
  stopId: string;
  name: string;
  isTurnaround: boolean;
}

export interface BoardingStopView extends StopViewBase {
  kind: "boarding";
  level: SeatLevel;
  arrivals: ArrivalView[];
}

export interface PassThroughStopView extends StopViewBase {
  kind: "passThrough";
}

export interface NoForecastStopView extends StopViewBase {
  kind: "noForecast";
}

export type StopView = BoardingStopView | PassThroughStopView | NoForecastStopView;

export interface DirectionView {
  id: Direction;
  label: string;
}

// 관측 시각은 계약상 KST(+09:00)로 계산하여 보여준다.
const KST_LOCAL_TIME = /T(\d{2}):(\d{2})[^Z]*\+09:00$/;
const CLOCK_TIME = /^(\d{1,2}):(\d{2})$/;

export function serviceStateFor(board: Board, directionInfo: DirectionInfo): ServiceState {
  switch (servicePhaseOf(directionInfo, board.observedAt)) {
    case "before":
    case "ended":
      // 실제 운영 시간이라고 하더라도 운행 중인 차량이 하나도 없으면 예측 화면을 보여주지 않는다.
      return "outOfService";
    // 관측한 값일 뿐 계산된 판정이 아니라서, 실제 운영 중 시간대에서만 이 값을 보여준다.
    case "running":
    // 시간표를 못 읽어 판정하지 못한 경우도 예보가 틀린 건 아니므로 판정화면을 보여주되 같은 기준으로 판단한다.
    case "undetermined":
      return board.vehiclesInService > 0 ? "running" : "outOfService";
  }
}

export function directionInfoFor(board: Board, preferred: Direction | null): DirectionInfo {
  const { directions } = board.route;
  return directions.find((directionInfo) => directionInfo.id === preferred) ?? directions[0];
}

export function stopViewsFor(board: Board, direction: Direction): StopView[] {
  const { turnSequence } = board.route;

  return board.stops
    .filter((stop) => stop.direction === direction)
    .sort((left, right) => left.sequence - right.sequence)
    .map((stop) => toStopView(stop, turnSequence));
}

export function directionViewsFor(board: Board): DirectionView[] {
  return board.route.directions.map((directionInfo) => ({
    id: directionInfo.id,
    label: `${directionInfo.originStopName} → ${directionInfo.terminalStopName}`,
  }));
}

function toStopView(stop: StopState, turnSequence: number | null): StopView {
  const { sequence, stopId, name } = stop;
  const isTurnaround = touchesTurnaround(sequence, turnSequence);

  if (!stop.boardingAllowed) {
    return { kind: "passThrough", sequence, stopId, name, isTurnaround };
  }

  const arrivals = arrivalViewsFor(stop.approachingVehicles);
  const representative = representativeArrival(arrivals);
  if (representative === undefined) {
    return { kind: "noForecast", sequence, stopId, name, isTurnaround };
  }

  return { kind: "boarding", sequence, stopId, name, isTurnaround, level: representative.level, arrivals };
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
    return observed > last && observed < first ? "ended" : "running";
  }
  if (observed < first) return "before";
  return observed > last ? "ended" : "running";
}

function minutesOf(parsed: RegExpExecArray | null): number | null {
  if (parsed === null) return null;
  const [, hours, minutes] = parsed;
  if (hours === undefined || minutes === undefined) return null;
  return Number(hours) * 60 + Number(minutes);
}
