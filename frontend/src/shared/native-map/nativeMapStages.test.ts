import { describe, expect, it } from "@jest/globals";
import { STARTUP_MARKS, type StartupMarkName } from "../startup/startupMarks";
import { createNativeMapStageTracker } from "./nativeMapStages";
import type { NativeMapCommands, NativeMapLayout } from "./protocol";

const camera = { center: { latitude: 37.39, longitude: 127.11 }, zoom: 14, heading: 0 };

function home(stopCount: number): NativeMapCommands["mountHome"] {
  const stops = Array.from({ length: stopCount }, (_, index) => ({
    id: `stop-${index}`,
    name: `정류장 ${index}`,
    coordinate: camera.center,
  }));
  return { content: { stations: { stops, selectedStopId: null }, paths: [], location: { coordinate: null } }, camera };
}

function layout(captureAll: boolean, transitioning = false): NativeMapLayout {
  return {
    viewport: { width: 390, height: 844, offsetTop: 0, offsetLeft: 0, scale: 1 },
    mapRect: { x: 0, y: 0, width: 390, height: 400 },
    webRects: [],
    captureAll,
    transitioning,
  };
}

function setup() {
  const names: StartupMarkName[] = [];
  const tracker = createNativeMapStageTracker((name) => void names.push(name));
  return { names, tracker };
}

const ready = { type: "ready", payload: {} } as const;
const ack = (appliedRevision: number) => ({ type: "ack", payload: { appliedRevision } }) as const;
const error = { type: "error", payload: { message: "auth" } } as const;

describe("native 지도 단계 추적", () => {
  it("mount, ready, 정류장, 열린 layout ack가 모두 모이면 정류장 선택 가능을 한 번 찍는다", () => {
    const { names, tracker } = setup();
    tracker.sent("mountHome", home(2), 1);
    tracker.received(ready);
    tracker.sent("layout", layout(false), 2);
    expect(names).toEqual([STARTUP_MARKS.mapNativeMount, STARTUP_MARKS.mapNativeReady]);
    tracker.received(ack(2));
    tracker.received(ack(2));
    tracker.sent("layout", layout(false), 3);
    tracker.received(ack(3));
    expect(names).toEqual([
      STARTUP_MARKS.mapNativeMount,
      STARTUP_MARKS.mapNativeReady,
      STARTUP_MARKS.mapStopsInteractive,
    ]);
  });

  it("ready 전에 온 ack만으로는 찍지 않고, ready가 오는 순간 찍는다", () => {
    const { names, tracker } = setup();
    tracker.sent("mountHome", home(1), 1);
    tracker.sent("layout", layout(false), 2);
    tracker.received(ack(2));
    expect(names).not.toContain(STARTUP_MARKS.mapStopsInteractive);
    tracker.received(ready);
    expect(names.at(-1)).toBe(STARTUP_MARKS.mapStopsInteractive);
  });

  it.each([
    ["captureAll", layout(true)],
    ["전환 중", layout(false, true)],
  ])("%s layout을 적용한 상태에서는 찍지 않는다", (_name, closed) => {
    const { names, tracker } = setup();
    tracker.sent("mountHome", home(1), 1);
    tracker.received(ready);
    tracker.sent("layout", closed, 2);
    tracker.received(ack(2));
    expect(names).not.toContain(STARTUP_MARKS.mapStopsInteractive);
  });

  it("열린 layout 뒤에 닫힌 layout까지 적용되면 찍지 않는다", () => {
    const { names, tracker } = setup();
    tracker.sent("mountHome", home(1), 1);
    tracker.received(ready);
    tracker.sent("layout", layout(false), 2);
    tracker.sent("layout", layout(true), 3);
    tracker.received(ack(3));
    expect(names).not.toContain(STARTUP_MARKS.mapStopsInteractive);
  });

  it("열린 layout보다 이전 revision의 ack는 세지 않는다", () => {
    const { names, tracker } = setup();
    tracker.sent("mountHome", home(1), 1);
    tracker.received(ready);
    tracker.sent("layout", layout(false), 2);
    tracker.received(ack(1));
    expect(names).not.toContain(STARTUP_MARKS.mapStopsInteractive);
  });

  it("정류장 0개면 찍지 않고, 정류장이 실린 updateHome 뒤에 찍는다", () => {
    const { names, tracker } = setup();
    tracker.sent("mountHome", home(0), 1);
    tracker.received(ready);
    tracker.sent("layout", layout(false), 2);
    tracker.received(ack(2));
    expect(names).not.toContain(STARTUP_MARKS.mapStopsInteractive);
    tracker.sent("updateHome", { paths: [] }, 3);
    expect(names).not.toContain(STARTUP_MARKS.mapStopsInteractive);
    tracker.sent("updateHome", { stations: home(1).content.stations }, 4);
    expect(names.at(-1)).toBe(STARTUP_MARKS.mapStopsInteractive);
  });

  it("판정보드 mount의 정류장도 센다", () => {
    const { names, tracker } = setup();
    const scene = { stops: [{}] } as unknown as NativeMapCommands["mount"];
    tracker.sent("mount", scene, 1);
    tracker.received(ready);
    tracker.sent("layout", layout(false), 2);
    tracker.received(ack(2));
    expect(names).toEqual([
      STARTUP_MARKS.mapNativeMount,
      STARTUP_MARKS.mapNativeReady,
      STARTUP_MARKS.mapStopsInteractive,
    ]);
  });

  it("error 뒤에는 찍지 않고, 다시 mount해 조건이 모이면 한 번만 찍는다", () => {
    const { names, tracker } = setup();
    tracker.sent("mountHome", home(1), 1);
    tracker.received(error);
    tracker.sent("layout", layout(false), 2);
    tracker.received(ack(2));
    expect(names).toEqual([STARTUP_MARKS.mapNativeMount, STARTUP_MARKS.mapNativeError]);
    tracker.sent("mountHome", home(1), 3);
    tracker.received(ack(2));
    expect(names).not.toContain(STARTUP_MARKS.mapStopsInteractive);
    tracker.received(ready);
    tracker.sent("layout", layout(false), 4);
    tracker.received(ack(4));
    tracker.sent("mountHome", home(1), 5);
    tracker.received(ready);
    tracker.sent("layout", layout(false), 6);
    tracker.received(ack(6));
    expect(names.filter((name) => name === STARTUP_MARKS.mapStopsInteractive)).toHaveLength(1);
  });
});
