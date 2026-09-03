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

const ISO_LOCAL_TIME = /T(\d{2}):(\d{2})[^Z]*$/;
const CLOCK_TIME = /^(\d{1,2}):(\d{2})$/;

export function serviceStateFor(board: Board, directionInfo: DirectionInfo): ServiceState {
  switch (servicePhaseOf(directionInfo, board.observedAt)) {
    case "before":
    case "ended":
      return "outOfService";
    // 시간표는 운행 중이라 해도 도는 차가 하나도 없으면 보여줄 예보가 없다.
    // 관측이지 단정이 아니라서, 시간표가 운행 중이라고 말할 때만 이 값을 본다.
    // 시간표를 못 읽어 판정하지 못한 경우도 예보가 틀린 건 아니므로 보드는 보여주되 같은 기준으로 판단한다.
    case "running":
    case "undetermined":
      return board.vehiclesInService > 0 ? "running" : "outOfService";
  }
}

// 선택한 방향이 이 노선에 있으면 그것, 없으면 첫 방향. 노선에 없는 방향이 화면에 남지 않게 한다.
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
  const observed = minutesOf(ISO_LOCAL_TIME.exec(observedAt));
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
