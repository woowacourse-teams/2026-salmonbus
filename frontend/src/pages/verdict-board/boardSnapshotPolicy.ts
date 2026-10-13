import type { CacheLifetime } from "@/shared/api/cacheControl";
import type { LatestSuccess } from "@/shared/api/polledResource";
import type { Board, LiveVehicles } from "@/shared/api/routeForecast.types";

export const BOARD_HISTORY_LIMIT = 2;
export const LIVE_VEHICLES_HISTORY_LIMIT = 3;

export interface ReceivedBoard {
  success: LatestSuccess<Board>;
  pairingDeadline: number;
}

export interface BoardSnapshot {
  board: LatestSuccess<Board>;
  liveVehicles: LatestSuccess<LiveVehicles> | null;
}

export interface BoardSnapshotStep {
  snapshot: BoardSnapshot | null;
  recheckAt: number | null;
}

interface BoardPair {
  board: ReceivedBoard;
  liveVehicles: LatestSuccess<LiveVehicles>;
}

export function receivedBoardOf(success: LatestSuccess<Board>, lifetime: CacheLifetime): ReceivedBoard {
  const freshSeconds =
    lifetime.noStore || lifetime.maxAgeSeconds === null ? 0 : Math.max(0, lifetime.maxAgeSeconds - lifetime.ageSeconds);

  return { success, pairingDeadline: success.receivedAt + freshSeconds * 1000 };
}

export function boardObservedAtOf(board: ReceivedBoard): number | null {
  return parsedTimeOf(board.success.body.observedAt);
}

export function liveVehiclesObservedAtOf(liveVehicles: LatestSuccess<LiveVehicles>): number | null {
  return parsedTimeOf(liveVehicles.body.observation.observedAt);
}

export function historyWith<T>(
  history: readonly T[],
  entry: T,
  limit: number,
  observedAtOf: (item: T) => number | null,
  shouldKeep: (item: T) => boolean = () => false,
): readonly T[] {
  const last = history.at(-1);
  if (last !== undefined && observedAtOf(last) === observedAtOf(entry)) return history;

  const next = [...history, entry];
  while (next.length > limit) {
    const oldestDroppable = next.findIndex((item) => !shouldKeep(item));
    if (oldestDroppable === -1) break;
    next.splice(oldestDroppable, 1);
  }
  return next;
}

export function isPairedWithAny(boards: readonly ReceivedBoard[], liveVehicles: LatestSuccess<LiveVehicles>): boolean {
  const time = liveVehiclesObservedAtOf(liveVehicles);
  return time !== null && boards.some((board) => boardObservedAtOf(board) === time);
}

export function boardSnapshotFor(
  boards: readonly ReceivedBoard[],
  liveVehicles: readonly LatestSuccess<LiveVehicles>[],
  now: number,
): BoardSnapshotStep {
  const latest = boards.at(-1);
  if (latest === undefined) return { snapshot: null, recheckAt: null };

  const pair = latestPairOf(boards, liveVehicles);
  if (pair === null) return boardWithoutVehicles(latest);
  if (pair.board === latest) return { snapshot: snapshotOf(pair), recheckAt: null };
  if (now < latest.pairingDeadline) return { snapshot: snapshotOf(pair), recheckAt: latest.pairingDeadline };

  return boardWithoutVehicles(latest);
}

function latestPairOf(
  boards: readonly ReceivedBoard[],
  liveVehicles: readonly LatestSuccess<LiveVehicles>[],
): BoardPair | null {
  const pairable = liveVehicles.filter(canPair).reverse();

  for (const board of [...boards].reverse()) {
    const observedAt = boardObservedAtOf(board);
    if (observedAt === null) continue;

    const match = pairable.find((candidate) => liveVehiclesObservedAtOf(candidate) === observedAt);
    if (match !== undefined) return { board, liveVehicles: match };
  }

  return null;
}

function canPair(liveVehicles: LatestSuccess<LiveVehicles>): boolean {
  return liveVehicles.body.observation.state !== "UNKNOWN" && liveVehiclesObservedAtOf(liveVehicles) !== null;
}

function snapshotOf(pair: BoardPair): BoardSnapshot {
  return { board: pair.board.success, liveVehicles: pair.liveVehicles };
}

function boardWithoutVehicles(board: ReceivedBoard): BoardSnapshotStep {
  return { snapshot: { board: board.success, liveVehicles: null }, recheckAt: null };
}

function parsedTimeOf(value: string | null): number | null {
  if (value === null) return null;

  const time = Date.parse(value);
  return Number.isNaN(time) ? null : time;
}
