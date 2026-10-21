import { describe, expect, it } from "@jest/globals";
import { PENDING_RESOURCE, type LatestSuccess, type PolledResource } from "@/shared/api/polledResource";
import type { Board, DirectionInfo, LiveVehicles, ObservationState } from "@/shared/api/routeForecast.types";
import { boardWith, downInfo, liveVehiclesWith, upInfo, vehicleAt } from "@/testing/fixtures";
import { serviceStateFor } from "./components/displayPolicy";
import {
  boardHistoriesWith,
  boardObservedAtOf,
  boardSnapshotFor,
  emptyBoardHistoriesFor,
  historyWith,
  isPairedWithAny,
  liveVehiclesObservedAtOf,
  receivedBoardOf,
  type ReceivedBoard,
} from "./boardSnapshotPolicy";

const BATCH_100 = "2026-10-13T08:00:00+09:00";
const BATCH_101 = "2026-10-13T08:00:15+09:00";
const BATCH_102 = "2026-10-13T08:00:30+09:00";
const BATCH_103 = "2026-10-13T08:00:45+09:00";

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
  it("마지막 응답과 같은 observation batch면 새 응답으로 바꾼다", () => {
    const first = liveVehiclesAt(BATCH_100, at("08:00:03"));
    const again = liveVehiclesAt(BATCH_100, at("08:00:18"));

    expect(historyWith([first], again, 3, liveVehiclesObservedAtOf)).toEqual([again]);
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

type Arrival =
  | { kind: "board"; at: number; observedAt: string; maxAgeSeconds?: number; board?: Board }
  | { kind: "boardFailed"; at: number }
  | { kind: "vehicles"; at: number; observedAt: string; state: ObservationState };

const LIFETIME_15S = { noStore: false, ageSeconds: 0 };

function board(time: string, observedAt: string, options: { maxAgeSeconds?: number; board?: Board } = {}): Arrival {
  return { kind: "board", at: at(time), observedAt, ...options };
}

function boardFailed(time: string): Arrival {
  return { kind: "boardFailed", at: at(time) };
}

function vehicles(time: string, observedAt: string, state: ObservationState = "VEHICLES_PRESENT"): Arrival {
  return { kind: "vehicles", at: at(time), observedAt, state };
}

function boardResourceAfter(previous: PolledResource<Board>, arrival: Arrival): PolledResource<Board> {
  if (arrival.kind === "board") {
    const body = arrival.board ?? boardWith({ observedAt: arrival.observedAt });
    return {
      status: "success",
      latestSuccess: successOf({ ...body, observedAt: arrival.observedAt }, arrival.at),
      lifetime: { ...LIFETIME_15S, maxAgeSeconds: arrival.maxAgeSeconds ?? 15 },
    };
  }
  if (arrival.kind === "boardFailed" && (previous.status === "success" || previous.status === "refetchError")) {
    return { status: "refetchError", failure: { kind: "network", cause: null }, latestSuccess: previous.latestSuccess };
  }
  return previous;
}

function replay(arrivals: readonly Arrival[], routeId = "R1", from = emptyBoardHistoriesFor(routeId)) {
  let boardResource: PolledResource<Board> = PENDING_RESOURCE;
  let liveVehicleResource: PolledResource<LiveVehicles> = PENDING_RESOURCE;

  return arrivals.reduce((histories, arrival) => {
    boardResource = boardResourceAfter(boardResource, arrival);
    if (arrival.kind === "vehicles") {
      liveVehicleResource = {
        status: "success",
        latestSuccess: liveVehiclesAt(arrival.observedAt, arrival.at, arrival.state),
        lifetime: { ...LIFETIME_15S, maxAgeSeconds: 15 },
      };
    }
    return boardHistoriesWith(histories, routeId, boardResource, liveVehicleResource);
  }, from);
}

function screenOf(arrivals: readonly Arrival[], time: string) {
  const histories = replay(arrivals);
  const step = boardSnapshotFor(histories.boards, histories.liveVehicles, at(time));
  return {
    boardBatch: step.snapshot?.board.body.observedAt ?? null,
    vehiclesBatch: step.snapshot?.liveVehicles?.body.observation.observedAt ?? null,
    recheckAt: step.recheckAt,
  };
}

describe("응답이 도착하는 순서대로 재생한 화면", () => {
  describe("평소 출근길 (수집 15초)", () => {
    it("차량이 먼저 오면 이전 쌍에 머물고, 예보가 따라오는 순간 셋이 함께 바뀐다", () => {
      const morning = [vehicles("08:00:03", BATCH_100), board("08:00:05", BATCH_100), vehicles("08:00:18", BATCH_101)];

      expect(screenOf(morning, "08:00:19")).toEqual({
        boardBatch: BATCH_100,
        vehiclesBatch: BATCH_100,
        recheckAt: null,
      });
      expect(screenOf([...morning, board("08:00:21", BATCH_101)], "08:00:21")).toEqual({
        boardBatch: BATCH_101,
        vehiclesBatch: BATCH_101,
        recheckAt: null,
      });
    });
  });

  describe("예보가 먼저 온 주기", () => {
    const boardFirst = [board("07:59:52", BATCH_100), vehicles("07:59:55", BATCH_100), board("08:00:07", BATCH_101)];

    it("예보의 max-age(15초)가 지나기 전에는 이전 쌍을 보여 주고, 다시 볼 시각을 알려 준다", () => {
      expect(screenOf(boardFirst, "08:00:12")).toEqual({
        boardBatch: BATCH_100,
        vehiclesBatch: BATCH_100,
        recheckAt: at("08:00:22"),
      });
    });

    it("max-age가 지나도 짝이 없으면 새 예보를 마커 없이 보여 준다", () => {
      expect(screenOf(boardFirst, "08:00:22")).toEqual({ boardBatch: BATCH_101, vehiclesBatch: null, recheckAt: null });
    });

    it("늦게라도 차량이 오면 다시 셋이 함께 맞는다", () => {
      expect(screenOf([...boardFirst, vehicles("08:00:25", BATCH_101)], "08:00:25")).toEqual({
        boardBatch: BATCH_101,
        vehiclesBatch: BATCH_101,
        recheckAt: null,
      });
    });

    it("같은 예보를 한 번 더 받아도 기다리는 기한은 처음 받은 때를 기준으로 한다", () => {
      expect(screenOf([...boardFirst, board("08:00:20", BATCH_101)], "08:00:21").recheckAt).toBe(at("08:00:22"));
    });
  });

  describe("예보 발행이 밀린 날", () => {
    const delayed = [
      board("08:00:05", BATCH_100),
      vehicles("08:00:03", BATCH_100),
      boardFailed("08:00:20"),
      vehicles("08:00:18", BATCH_101),
      vehicles("08:00:33", BATCH_102),
      vehicles("08:00:48", "2026-10-13T08:00:45+09:00"),
    ];

    it("차량이 세 번 넘게 새로 와도 마지막 쌍에 머문다", () => {
      expect(screenOf(delayed, "08:00:49")).toEqual({
        boardBatch: BATCH_100,
        vehiclesBatch: BATCH_100,
        recheckAt: null,
      });
    });

    it("예보가 observation batch 두 개를 건너뛰고 따라와도, 들고 있던 차량과 바로 짝이 맞는다", () => {
      const caughtUp = [...delayed, board("08:00:52", "2026-10-13T08:00:45+09:00")];

      expect(screenOf(caughtUp, "08:00:52")).toEqual({
        boardBatch: "2026-10-13T08:00:45+09:00",
        vehiclesBatch: "2026-10-13T08:00:45+09:00",
        recheckAt: null,
      });
    });
  });

  describe("차량 응답이 끊긴 채 예보만 계속 올 때", () => {
    const outage = [
      vehicles("08:00:03", BATCH_100),
      board("08:00:05", BATCH_100),
      board("08:00:20", BATCH_101),
      board("08:00:37", BATCH_102),
      board("08:00:52", BATCH_103),
    ];

    it("첫 기한이 지난 뒤 다음 예보가 와도 이전 쌍으로 되돌아가지 않는다", () => {
      expect(screenOf(outage.slice(0, 4), "08:00:38")).toEqual({
        boardBatch: BATCH_102,
        vehiclesBatch: null,
        recheckAt: null,
      });
    });

    it("예보가 계속 와도 마커 없이 가장 최근 예보를 보여 준다", () => {
      expect(screenOf(outage, "08:00:53")).toEqual({ boardBatch: BATCH_103, vehiclesBatch: null, recheckAt: null });
    });

    it("차량이 다시 오면 같은 observation batch의 예보와 짝이 맞는 순간 마커가 돌아온다", () => {
      const recovered = [...outage, vehicles("08:01:00", BATCH_103)];

      expect(screenOf(recovered, "08:01:00")).toEqual({
        boardBatch: BATCH_103,
        vehiclesBatch: BATCH_103,
        recheckAt: null,
      });
    });
  });

  describe("수집 중 빈 응답", () => {
    it("UNKNOWN 빈 응답이 와도 직전 차량으로 마커를 그대로 그린다", () => {
      const collecting = [
        vehicles("08:00:03", BATCH_100),
        board("08:00:05", BATCH_100),
        vehicles("08:00:18", BATCH_100, "UNKNOWN"),
      ];
      const histories = replay(collecting);
      const step = boardSnapshotFor(histories.boards, histories.liveVehicles, at("08:00:19"));

      expect(step.snapshot?.liveVehicles?.body.observation.state).toBe("VEHICLES_PRESENT");
      expect(step.snapshot?.liveVehicles?.body.vehicles).toHaveLength(1);
    });
  });

  describe("새벽 (수집 600초)", () => {
    it("예보가 먼저 오면 max-age 600초 동안 이전 쌍에 머문다", () => {
      const dawn = [
        board("02:00:00", "2026-10-13T01:59:55+09:00", { maxAgeSeconds: 600 }),
        vehicles("02:00:01", "2026-10-13T01:59:55+09:00"),
        board("02:10:05", "2026-10-13T02:09:55+09:00", { maxAgeSeconds: 600 }),
      ];

      expect(screenOf(dawn, "02:19:00")).toEqual({
        boardBatch: "2026-10-13T01:59:55+09:00",
        vehiclesBatch: "2026-10-13T01:59:55+09:00",
        recheckAt: at("02:20:05"),
      });
    });
  });

  describe("처음 들어왔을 때", () => {
    it("차량이 예보보다 새 observation batch면 예보부터 보여 주고, 짝이 생기면 마커가 붙는다", () => {
      const entering = [vehicles("08:00:18", BATCH_101), board("08:00:18", BATCH_100)];

      expect(screenOf(entering, "08:00:18")).toEqual({ boardBatch: BATCH_100, vehiclesBatch: null, recheckAt: null });
      expect(screenOf([...entering, board("08:00:21", BATCH_101)], "08:00:21")).toEqual({
        boardBatch: BATCH_101,
        vehiclesBatch: BATCH_101,
        recheckAt: null,
      });
    });
  });

  describe("노선을 바꿨을 때", () => {
    it("이전 노선의 기록은 버리고 새 노선의 응답만 쓴다", () => {
      const previousRoute = replay([vehicles("08:00:03", BATCH_100), board("08:00:05", BATCH_100)], "R1");
      const nextRoute = boardHistoriesWith(previousRoute, "R2", PENDING_RESOURCE, PENDING_RESOURCE);

      expect(nextRoute).toEqual(emptyBoardHistoriesFor("R2"));
    });
  });

  describe("막차가 떠난 뒤 처음 들어왔을 때", () => {
    const LATE_BATCH_100 = "2026-10-13T23:40:00+09:00";
    const LATE_BATCH_101 = "2026-10-13T23:40:20+09:00";

    const entering = [vehicles("23:40:21", LATE_BATCH_101), board("23:40:21", LATE_BATCH_100)];
    const caughtUp = [...entering, board("23:40:26", LATE_BATCH_101)];

    function screenWithServiceOf(arrivals: readonly Arrival[], time: string, direction: DirectionInfo) {
      const histories = replay(arrivals);
      const { snapshot } = boardSnapshotFor(histories.boards, histories.liveVehicles, at(time));
      if (snapshot === null) return null;
      return {
        vehiclesBatch: snapshot.liveVehicles?.body.observation.observedAt ?? null,
        service: serviceStateFor(snapshot.board.body, direction, snapshot.liveVehicles?.body ?? null),
      };
    }

    it("차량이 예보보다 새 observation batch라 짝이 없어도 운행 종료로 바뀌지 않는다", () => {
      expect(screenWithServiceOf(entering, "23:40:21", upInfo)).toEqual({ vehiclesBatch: null, service: "running" });
    });

    it("예보가 따라와 짝이 맞아도 이 방향 차량이 있으니 판정 보드를 유지한다", () => {
      expect(screenWithServiceOf(caughtUp, "23:40:26", upInfo)).toEqual({
        vehiclesBatch: LATE_BATCH_101,
        service: "running",
      });
    });

    it("이 방향 차량이 없으면 짝을 기다리는 동안만 판정 보드를 보여 주고, 짝이 맞는 순간 운행 종료로 바뀐다", () => {
      expect(screenWithServiceOf(entering, "23:40:21", downInfo)).toEqual({ vehiclesBatch: null, service: "running" });
      expect(screenWithServiceOf(caughtUp, "23:40:26", downInfo)).toEqual({
        vehiclesBatch: LATE_BATCH_101,
        service: "outOfService",
      });
    });
  });
});
