import { describe, expect, it } from "@jest/globals";
import { boardWith } from "@/testing/fixtures";
import type { BoardScreen, ExpandableStopView } from "../components/displayPolicy";
import {
  nextBoardVisitStep,
  nextStopExpandStep,
  type BoardObservation,
  type BoardVisit,
  type BoardVisitEvent,
  type StopExpandStep,
} from "./boardVisitPolicy";

const MINUTE_MS = 60 * 1000;

const board = boardWith({ vehiclesInService: 3 });
const loading: BoardScreen = { kind: "loading" };
const forecast: BoardScreen = { kind: "forecast", board, direction: "UP" };
const outOfService: BoardScreen = { kind: "outOfService", board };
const timedOut: BoardScreen = { kind: "error", failure: { kind: "timeout" } };

function observed(overrides: Partial<BoardObservation>): BoardObservation {
  return {
    routeId: "R1",
    screen: loading,
    receivedAt: null,
    visible: true,
    serverNow: Date.parse("2026-09-01T08:13:10+09:00"),
    elapsedNow: 0,
    wallNow: 0,
    ...overrides,
  };
}

function eventsOf(observations: BoardObservation[]): BoardVisitEvent[] {
  let visit: BoardVisit | null = null;
  const events: BoardVisitEvent[] = [];
  for (const observation of observations) {
    const step = nextBoardVisitStep(visit, observation);
    visit = step.visit;
    if (step.event !== null) events.push(step.event);
  }
  return events;
}

function namesOf(events: BoardVisitEvent[]): string[] {
  return events.map((event) =>
    event.name === "board_unavailable" ? `board_unavailable:${event.properties.reason}` : event.name,
  );
}

describe("nextBoardVisitStep", () => {
  it("예보가 처음 뜨면 board_viewed를 보내고, 들어온 뒤 걸린 시간을 남긴다", () => {
    const events = eventsOf([
      observed({ screen: loading, elapsedNow: 100 }),
      observed({ screen: forecast, receivedAt: 1_000, elapsedNow: 1_300 }),
    ]);

    expect(namesOf(events)).toEqual(["board_viewed"]);
    expect(events[0]?.properties).toMatchObject({ route_id: "R1", load_ms: 1_200 });
  });

  it("폴링으로 예보가 여러 번 새로 와도 board_viewed는 한 번만 보낸다", () => {
    const events = eventsOf([
      observed({ screen: loading }),
      observed({ screen: forecast, receivedAt: 1_000 }),
      observed({ screen: forecast, receivedAt: 2_000 }),
      observed({ screen: forecast, receivedAt: 3_000 }),
    ]);

    expect(namesOf(events)).toEqual(["board_viewed"]);
  });

  it("운행 시간이 아니면 board_unavailable을 한 번만 보낸다", () => {
    const events = eventsOf([
      observed({ screen: loading }),
      observed({ screen: outOfService, receivedAt: 1_000 }),
      observed({ screen: outOfService, receivedAt: 2_000 }),
    ]);

    expect(namesOf(events)).toEqual(["board_unavailable:outOfService"]);
    expect(events[0]?.properties).toEqual({ route_id: "R1", reason: "outOfService", vehicles_in_service: 3 });
  });

  it("예보를 본 방문에서는 운행이 끝나도 board_unavailable을 보내지 않는다", () => {
    const events = eventsOf([
      observed({ screen: loading }),
      observed({ screen: forecast, receivedAt: 1_000 }),
      observed({ screen: outOfService, receivedAt: 2_000 }),
    ]);

    expect(namesOf(events)).toEqual(["board_viewed"]);
  });

  it("첫 요청이 실패하면 board_unavailable을 보내고, 다시 성공하면 board_viewed를 보낸다", () => {
    const events = eventsOf([
      observed({ screen: loading }),
      observed({ screen: { kind: "error", failure: { kind: "timeout" } } }),
      observed({ screen: forecast, receivedAt: 1_000 }),
    ]);

    expect(namesOf(events)).toEqual(["board_unavailable:error", "board_viewed"]);
    expect(events[0]?.properties).toEqual({ route_id: "R1", reason: "error", error_kind: "timeout" });
  });

  it("사용자가 떠나서 취소된 요청은 보내지 않는다", () => {
    const events = eventsOf([
      observed({ screen: loading }),
      observed({ screen: { kind: "error", failure: { kind: "aborted" } } }),
    ]);

    expect(events).toEqual([]);
  });

  it("다른 노선으로 바뀌면 새 방문으로 센다", () => {
    const events = eventsOf([
      observed({ routeId: "R1", screen: forecast, receivedAt: 1_000 }),
      observed({ routeId: "R2", screen: loading }),
      observed({ routeId: "R2", screen: forecast, receivedAt: 2_000 }),
    ]);

    expect(namesOf(events)).toEqual(["board_viewed", "board_viewed"]);
  });

  it("탭이 숨겨진 동안에는 보내지 않는다", () => {
    const events = eventsOf([
      observed({ screen: loading }),
      observed({ screen: forecast, receivedAt: 1_000, visible: false }),
    ]);

    expect(events).toEqual([]);
  });

  it("탭을 30분 안에 다시 열면 같은 방문이라 다시 보내지 않는다", () => {
    const events = eventsOf([
      observed({ screen: forecast, receivedAt: 1_000, wallNow: 0 }),
      observed({ screen: forecast, receivedAt: 1_000, visible: false, wallNow: MINUTE_MS }),
      observed({ screen: forecast, receivedAt: 1_000, wallNow: 29 * MINUTE_MS }),
      observed({ screen: forecast, receivedAt: 2_000, wallNow: 29 * MINUTE_MS }),
    ]);

    expect(namesOf(events)).toEqual(["board_viewed"]);
  });

  it("탭을 30분 넘게 숨겼다 다시 열면 새 예보를 받은 뒤에 한 번 더 보낸다", () => {
    const events = eventsOf([
      observed({ screen: forecast, receivedAt: 1_000, wallNow: 0 }),
      observed({ screen: forecast, receivedAt: 1_000, visible: false, wallNow: MINUTE_MS }),
      observed({ screen: forecast, receivedAt: 1_000, wallNow: 31 * MINUTE_MS }),
      observed({ screen: forecast, receivedAt: 2_000, wallNow: 31 * MINUTE_MS }),
    ]);

    expect(namesOf(events)).toEqual(["board_viewed", "board_viewed"]);
  });

  it("첫 요청이 실패한 채 30분 넘게 숨겼다 열면 이전 오류 대신 새 요청의 실패를 보낸다", () => {
    const events = eventsOf([
      observed({ screen: timedOut, wallNow: 0 }),
      observed({ screen: timedOut, visible: false, wallNow: MINUTE_MS }),
      observed({ screen: timedOut, wallNow: 31 * MINUTE_MS }),
      observed({
        screen: { kind: "error", failure: { kind: "network", cause: new TypeError("Failed to fetch") } },
        wallNow: 31 * MINUTE_MS,
      }),
    ]);

    expect(namesOf(events)).toEqual(["board_unavailable:error", "board_unavailable:error"]);
    expect(events.map((event) => event.properties)).toEqual([
      { route_id: "R1", reason: "error", error_kind: "timeout" },
      { route_id: "R1", reason: "error", error_kind: "network" },
    ]);
  });

  it("첫 요청이 실패한 채 30분 넘게 숨겼다 열었다가 새 요청이 성공하면 board_viewed만 더 보낸다", () => {
    const events = eventsOf([
      observed({ screen: timedOut, wallNow: 0 }),
      observed({ screen: timedOut, visible: false, wallNow: MINUTE_MS }),
      observed({ screen: timedOut, wallNow: 31 * MINUTE_MS }),
      observed({ screen: forecast, receivedAt: 1_000, wallNow: 31 * MINUTE_MS }),
    ]);

    expect(namesOf(events)).toEqual(["board_unavailable:error", "board_viewed"]);
  });
});

describe("nextStopExpandStep", () => {
  const stop: ExpandableStopView = {
    kind: "noVehicle",
    sequence: 2,
    stopId: "S2",
    name: "정류장2",
    isTurnaround: false,
  };
  const expand = { stop, direction: "UP", rowPosition: 1 } as const;

  function visitAfter(observations: BoardObservation[], from: BoardVisit | null = null): BoardVisit {
    let visit = from;
    for (const observation of observations) {
      visit = nextBoardVisitStep(visit, observation).visit;
    }
    if (visit === null) throw new Error("관측이 없어 방문을 만들 수 없습니다");
    return visit;
  }

  function expandOnce(visit: BoardVisit | null): StopExpandStep {
    const step = nextStopExpandStep(visit, expand);
    if (step === null) throw new Error("stop_expanded를 보내지 않았습니다");
    return step;
  }

  it("같은 방문에서 정류장을 펼칠 때마다 expand_index가 1씩 늘어난다", () => {
    const first = expandOnce(visitAfter([observed({ screen: forecast, receivedAt: 1_000 })]));
    const second = expandOnce(first.visit);

    expect([first.properties.expand_index, second.properties.expand_index]).toEqual([1, 2]);
  });

  it("방문 기록이 없으면 stop_expanded를 보내지 않는다", () => {
    expect(nextStopExpandStep(null, expand)).toBeNull();
  });

  it("다른 노선으로 바뀌어 새 방문이 시작되면 expand_index를 1부터 다시 센다", () => {
    const expanded = expandOnce(visitAfter([observed({ routeId: "R1", screen: forecast, receivedAt: 1_000 })]));
    const nextVisit = visitAfter(
      [observed({ routeId: "R2", screen: loading }), observed({ routeId: "R2", screen: forecast, receivedAt: 2_000 })],
      expanded.visit,
    );

    expect(expandOnce(nextVisit).properties).toMatchObject({ route_id: "R2", expand_index: 1 });
  });

  it("탭을 30분 넘게 숨겼다 돌아와 새 예보를 받기 전에 펼치면 stop_expanded를 보내지 않는다", () => {
    const expanded = expandOnce(visitAfter([observed({ screen: forecast, receivedAt: 1_000, wallNow: 0 })]));
    const returned = visitAfter(
      [
        observed({ screen: forecast, receivedAt: 1_000, visible: false, wallNow: 0 }),
        observed({ screen: forecast, receivedAt: 1_000, visible: true, wallNow: 31 * MINUTE_MS }),
      ],
      expanded.visit,
    );

    expect(nextStopExpandStep(returned, expand)).toBeNull();
  });

  it("탭을 30분 넘게 숨겼다 돌아와 새 예보로 board_viewed를 보낸 뒤 펼치면 expand_index를 1부터 센다", () => {
    const expanded = expandOnce(visitAfter([observed({ screen: forecast, receivedAt: 1_000, wallNow: 0 })]));
    const refreshed = visitAfter(
      [
        observed({ screen: forecast, receivedAt: 1_000, visible: false, wallNow: 0 }),
        observed({ screen: forecast, receivedAt: 1_000, visible: true, wallNow: 31 * MINUTE_MS }),
        observed({ screen: forecast, receivedAt: 2_000, visible: true, wallNow: 31 * MINUTE_MS }),
      ],
      expanded.visit,
    );

    expect(expandOnce(refreshed).properties.expand_index).toBe(1);
  });

  it("탭을 30분 안에 다시 열면 같은 방문이라 이어서 센다", () => {
    const expanded = expandOnce(visitAfter([observed({ screen: forecast, receivedAt: 1_000, wallNow: 0 })]));
    const returned = visitAfter(
      [
        observed({ screen: forecast, receivedAt: 1_000, visible: false, wallNow: 0 }),
        observed({ screen: forecast, receivedAt: 1_000, visible: true, wallNow: 10 * MINUTE_MS }),
      ],
      expanded.visit,
    );

    expect(expandOnce(returned).properties.expand_index).toBe(2);
  });
});
