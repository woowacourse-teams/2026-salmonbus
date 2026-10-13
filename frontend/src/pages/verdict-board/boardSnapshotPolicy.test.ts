import { describe, expect, it } from "@jest/globals";
import type { LatestSuccess } from "@/shared/api/polledResource";
import type { LiveVehicles, ObservationState } from "@/shared/api/routeForecast.types";
import { boardWith, liveVehiclesWith, vehicleAt } from "@/testing/fixtures";
import {
  boardObservedAtOf,
  boardSnapshotFor,
  historyWith,
  isPairedWithAny,
  liveVehiclesObservedAtOf,
  receivedBoardOf,
  type ReceivedBoard,
} from "./boardSnapshotPolicy";

const BATCH_100 = "2026-10-13T08:00:00+09:00";
const BATCH_101 = "2026-10-13T08:00:15+09:00";
const BATCH_102 = "2026-10-13T08:00:30+09:00";

function at(time: string): number {
  return Date.parse(`2026-10-13T${time}+09:00`);
}

function successOf<T>(body: T, receivedAt: number): LatestSuccess<T> {
  return { body, clock: null, receivedAt };
}

function boardAt(observedAt: string, receivedAt: number, maxAgeSeconds = 15): ReceivedBoard {
  return receivedBoardOf(successOf(boardWith({ observedAt }), receivedAt), {
    noStore: false,
    maxAgeSeconds,
    ageSeconds: 0,
  });
}

function liveVehiclesAt(
  observedAt: string,
  receivedAt: number,
  state: ObservationState = "VEHICLES_PRESENT",
): LatestSuccess<LiveVehicles> {
  const vehicles = state === "VEHICLES_PRESENT" ? [vehicleAt(2)] : [];
  return successOf(liveVehiclesWith(vehicles, { observation: { state, observedAt, staleAt: null } }), receivedAt);
}

describe("boardSnapshotFor", () => {
  it("예보와 차량이 같은 observation batch면 한 쌍으로 올린다", () => {
    const board = boardAt(BATCH_100, at("08:00:05"));
    const liveVehicles = liveVehiclesAt(BATCH_100, at("08:00:03"));

    const step = boardSnapshotFor([board], [liveVehicles], at("08:00:06"));

    expect(step).toEqual({ snapshot: { board: board.success, liveVehicles }, recheckAt: null });
  });

  it("차량이 먼저 새 observation batch를 받으면, 예보가 따라올 때까지 이전 쌍을 올린다", () => {
    const board = boardAt(BATCH_100, at("08:00:05"));
    const previousVehicles = liveVehiclesAt(BATCH_100, at("07:59:58"));
    const newerVehicles = liveVehiclesAt(BATCH_101, at("08:00:18"));

    const step = boardSnapshotFor([board], [previousVehicles, newerVehicles], at("08:00:19"));

    expect(step.snapshot).toEqual({ board: board.success, liveVehicles: previousVehicles });
  });

  it("예보가 따라오면 마커, 정류장 수, 좌석이 새 observation batch로 한 번에 바뀐다", () => {
    const previousBoard = boardAt(BATCH_100, at("08:00:05"));
    const newerBoard = boardAt(BATCH_101, at("08:00:21"));
    const newerVehicles = liveVehiclesAt(BATCH_101, at("08:00:18"));

    const step = boardSnapshotFor(
      [previousBoard, newerBoard],
      [liveVehiclesAt(BATCH_100, at("07:59:58")), newerVehicles],
      at("08:00:22"),
    );

    expect(step).toEqual({ snapshot: { board: newerBoard.success, liveVehicles: newerVehicles }, recheckAt: null });
  });

  it("예보가 먼저 새 observation batch를 받으면, 그 예보의 max-age가 지나기 전까지 이전 쌍에 머물고 다시 볼 시각을 알려 준다", () => {
    const previousBoard = boardAt(BATCH_100, at("07:59:52"));
    const newerBoard = boardAt(BATCH_101, at("08:00:07"), 15);
    const previousVehicles = liveVehiclesAt(BATCH_100, at("08:00:03"));

    const step = boardSnapshotFor([previousBoard, newerBoard], [previousVehicles], at("08:00:12"));

    expect(step).toEqual({
      snapshot: { board: previousBoard.success, liveVehicles: previousVehicles },
      recheckAt: at("08:00:22"),
    });
  });

  it("예보의 max-age가 지나도 짝이 오지 않으면, 새 예보를 마커 없이 올린다", () => {
    const previousBoard = boardAt(BATCH_100, at("07:59:52"));
    const newerBoard = boardAt(BATCH_101, at("08:00:07"), 15);

    const step = boardSnapshotFor(
      [previousBoard, newerBoard],
      [liveVehiclesAt(BATCH_100, at("08:00:03"))],
      at("08:00:22"),
    );

    expect(step).toEqual({ snapshot: { board: newerBoard.success, liveVehicles: null }, recheckAt: null });
  });

  it("max-age가 없는 예보가 먼저 오면 기다리지 않고 마커 없이 올린다", () => {
    const previousBoard = boardAt(BATCH_100, at("07:59:52"));
    const newerBoard = receivedBoardOf(successOf(boardWith({ observedAt: BATCH_101 }), at("08:00:07")), {
      noStore: true,
      maxAgeSeconds: null,
      ageSeconds: 0,
    });

    const step = boardSnapshotFor(
      [previousBoard, newerBoard],
      [liveVehiclesAt(BATCH_100, at("08:00:03"))],
      at("08:00:07"),
    );

    expect(step.snapshot).toEqual({ board: newerBoard.success, liveVehicles: null });
  });

  it("수집 중이라 비어 있는 UNKNOWN 차량 응답은 observedAt이 같아도 짝으로 고르지 않는다", () => {
    const board = boardAt(BATCH_100, at("08:00:05"));
    const normal = liveVehiclesAt(BATCH_100, at("08:00:03"));
    const collecting = liveVehiclesAt(BATCH_100, at("08:00:18"), "UNKNOWN");

    const step = boardSnapshotFor([board], [normal, collecting], at("08:00:19"));

    expect(step.snapshot?.liveVehicles).toBe(normal);
  });

  it("차량이 없다고 관측된 응답도 같은 observation batch면 짝이 된다", () => {
    const board = boardAt(BATCH_100, at("08:00:05"));
    const empty = liveVehiclesAt(BATCH_100, at("08:00:03"), "NO_VEHICLES_OBSERVED");

    const step = boardSnapshotFor([board], [empty], at("08:00:06"));

    expect(step.snapshot?.liveVehicles).toBe(empty);
  });

  it("처음 받은 차량 응답이 예보보다 새 observation batch면, 짝이 생길 때까지 예보를 마커 없이 올린다", () => {
    const board = boardAt(BATCH_100, at("08:00:05"));

    const step = boardSnapshotFor([board], [liveVehiclesAt(BATCH_101, at("08:00:05"))], at("08:00:06"));

    expect(step).toEqual({ snapshot: { board: board.success, liveVehicles: null }, recheckAt: null });
  });

  it("예보가 멈춘 채 차량이 새로 와도, 들고 있는 차량 응답에 짝이 남아 있으면 마지막 쌍에 머문다", () => {
    const board = boardAt(BATCH_100, at("08:00:05"));
    const pairedVehicles = liveVehiclesAt(BATCH_100, at("08:00:03"));

    const step = boardSnapshotFor(
      [board],
      [pairedVehicles, liveVehiclesAt(BATCH_101, at("08:00:18")), liveVehiclesAt(BATCH_102, at("08:00:33"))],
      at("08:00:34"),
    );

    expect(step.snapshot).toEqual({ board: board.success, liveVehicles: pairedVehicles });
  });

  it("observedAt 표기가 달라도 같은 시각이면 같은 observation batch로 본다", () => {
    const board = boardAt("2026-10-13T08:00:00.224209+09:00", at("08:00:05"));
    const liveVehicles = liveVehiclesAt("2026-10-12T23:00:00.224Z", at("08:00:03"));

    const step = boardSnapshotFor([board], [liveVehicles], at("08:00:06"));

    expect(step.snapshot?.liveVehicles).toBe(liveVehicles);
  });

  it("받은 예보가 없으면 아무것도 올리지 않는다", () => {
    expect(boardSnapshotFor([], [liveVehiclesAt(BATCH_100, at("08:00:03"))], at("08:00:06"))).toEqual({
      snapshot: null,
      recheckAt: null,
    });
  });
});

describe("receivedBoardOf", () => {
  it("짝을 기다리는 기한은 받은 시각에 남은 max-age를 더한 시각이다", () => {
    const board = receivedBoardOf(successOf(boardWith(), at("08:00:00")), {
      noStore: false,
      maxAgeSeconds: 20,
      ageSeconds: 5,
    });

    expect(board.pairingDeadline).toBe(at("08:00:15"));
  });

  it("no-store 응답은 받은 시각이 곧 기한이다", () => {
    const board = receivedBoardOf(successOf(boardWith(), at("08:00:00")), {
      noStore: true,
      maxAgeSeconds: 20,
      ageSeconds: 0,
    });

    expect(board.pairingDeadline).toBe(at("08:00:00"));
  });
});

describe("historyWith", () => {
  it("마지막 응답과 같은 observation batch면 처음 받은 것을 그대로 둔다", () => {
    const first = liveVehiclesAt(BATCH_100, at("08:00:03"));
    const history = historyWith([first], liveVehiclesAt(BATCH_100, at("08:00:18")), 3, liveVehiclesObservedAtOf);

    expect(history).toEqual([first]);
  });

  it("개수를 넘으면 가장 오래된 응답부터 버린다", () => {
    const boards = [boardAt(BATCH_100, at("08:00:05")), boardAt(BATCH_101, at("08:00:20"))];
    const newest = boardAt(BATCH_102, at("08:00:35"));

    expect(historyWith(boards, newest, 2, boardObservedAtOf)).toEqual([boards[1], newest]);
  });

  it("예보와 짝인 차량 응답은 개수를 넘어도 남기고, 그다음으로 오래된 응답을 버린다", () => {
    const boards = [boardAt(BATCH_100, at("08:00:05"))];
    const paired = liveVehiclesAt(BATCH_100, at("08:00:03"));
    const history = [paired, liveVehiclesAt(BATCH_101, at("08:00:18")), liveVehiclesAt(BATCH_102, at("08:00:33"))];
    const newest = liveVehiclesAt("2026-10-13T08:00:45+09:00", at("08:00:48"));

    const keepsPaired = (item: LatestSuccess<LiveVehicles>) => isPairedWithAny(boards, item);

    expect(historyWith(history, newest, 3, liveVehiclesObservedAtOf, keepsPaired)).toEqual([
      paired,
      history[2],
      newest,
    ]);
  });
});

describe("예보가 멈춘 채 차량만 계속 올 때", () => {
  it("차량 응답이 세 번 넘게 새로 와도 마커, 정류장 수, 좌석은 마지막 쌍에 머문다", () => {
    const boards = [boardAt(BATCH_100, at("08:00:05"))];
    const paired = liveVehiclesAt(BATCH_100, at("08:00:03"));
    const arrivals = [
      liveVehiclesAt(BATCH_101, at("08:00:18")),
      liveVehiclesAt(BATCH_102, at("08:00:33")),
      liveVehiclesAt("2026-10-13T08:00:45+09:00", at("08:00:48")),
    ];

    const history = arrivals.reduce<readonly LatestSuccess<LiveVehicles>[]>(
      (current, arrival) =>
        historyWith(current, arrival, 3, liveVehiclesObservedAtOf, (item) => isPairedWithAny(boards, item)),
      [paired],
    );

    expect(boardSnapshotFor(boards, history, at("08:00:49")).snapshot).toEqual({
      board: boards[0]?.success,
      liveVehicles: paired,
    });
  });
});
