import type { Board, Direction, DirectionInfo, StopState } from "@/shared/api/routeForecast.types";
import { arrivalViewsFor, representativeArrival, type ArrivalView } from "./arrivalPolicy";
import type { SeatLevel } from "./seatGrade";

export type ServiceState = "running" | "outOfService";

type ServicePhase = "before" | "running" | "ended";

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

const ISO_LOCAL_TIME = /T(\d{2}):(\d{2})[^Z]*$/;
const CLOCK_TIME = /^(\d{1,2}):(\d{2})$/;

export function serviceStateFor(board: Board, direction: Direction): ServiceState {
  const phase = servicePhaseOf(directionInfoOf(board, direction), board.observedAt);
  if (phase !== "running") {
    return "outOfService";
  }

  // 시간표는 운행 중이라 해도 도는 차가 하나도 없으면 보여줄 예보가 없다.
  // 관측이지 단정이 아니라서, 시간표가 운행 중이라고 말할 때만 이 값을 본다.
  return board.vehiclesInService > 0 ? "running" : "outOfService";
}

export function stopViewsFor(board: Board, direction: Direction): StopView[] {
  const { turnSequence } = board.route;

  return board.stops
    .filter((stop) => stop.direction === direction)
    .sort((left, right) => left.sequence - right.sequence)
    .map((stop) => toStopView(stop, turnSequence));
}

export function directionViewsFor(board: Board): DirectionView[] {
  return board.route.directions.map((info) => ({
    id: info.id,
    label: `${info.originStopName} → ${info.terminalStopName}`,
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

function directionInfoOf(board: Board, direction: Direction): DirectionInfo | undefined {
  return board.route.directions.find((directionInfo) => directionInfo.id === direction);
}

function servicePhaseOf(info: DirectionInfo | undefined, observedAt: string): ServicePhase {
  if (info === undefined) return "running";

  const observed = minutesOf(ISO_LOCAL_TIME.exec(observedAt));
  const first = minutesOf(CLOCK_TIME.exec(info.firstDepartureTime));
  const last = minutesOf(CLOCK_TIME.exec(info.lastDepartureTime));
  if (observed === null || first === null || last === null) return "running";

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
