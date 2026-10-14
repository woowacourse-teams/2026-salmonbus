import { describe, expect, it, jest } from "@jest/globals";
import { boardWith, liveVehiclesWith, vehicleAt } from "@/testing/fixtures";
import type { Phase } from "@/shared/api/routeForecast.types";
import { liveVehicleViewsFor, type LiveVehicleView } from "../components/liveVehiclePolicy";
import { createLiveVehicleMotionStore, type LiveVehicleMotionStore } from "./useLiveVehicleMotion";

const DURATION = 20_000;

function viewFor(phase: Phase): LiveVehicleView {
  return liveVehicleViewsFor(
    boardWith(),
    liveVehiclesWith([vehicleAt(1, { vehicleId: "moving-bus", phase })]),
    "UP",
  )[0]!;
}

function positionOf(store: LiveVehicleMotionStore): number {
  return store.frames[0]!.sequencePosition;
}

describe("shared live vehicle motion", () => {
  it("같은 clock의 정규화 좌표를 지도와 목록 구독자에게 선형으로 전달한다", () => {
    const store = createLiveVehicleMotionStore();
    const mapPositions: number[] = [];
    const listPositions: number[] = [];
    store.subscribe(() => mapPositions.push(positionOf(store)));
    store.subscribe(() => listPositions.push(positionOf(store)));

    store.update([viewFor("DEPARTED")], DURATION, 0);
    expect(positionOf(store)).toBeCloseTo(1.08);
    expect(store.advance(DURATION / 2)).toBe(true);
    expect(positionOf(store)).toBeCloseTo(1.19);
    expect(store.advance(DURATION)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(1.3);
    expect(mapPositions).toEqual(listPositions);
  });

  it("같은 구간의 좌석·freshness 갱신은 진행 시간과 목표 위치를 다시 시작하지 않는다", () => {
    const store = createLiveVehicleMotionStore();
    const view = viewFor("DEPARTED");
    store.update([view], DURATION, 0);
    store.advance(5_000);
    expect(positionOf(store)).toBeCloseTo(1.135);

    store.update(
      [{ ...view, vehicle: { ...view.vehicle, seat: { kind: "EXACT", remaining: 0 } } }],
      DURATION * 4,
      5_000,
    );
    store.advance(10_000);
    expect(positionOf(store)).toBeCloseTo(1.19);
    expect(store.advance(DURATION)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(1.3);

    store.update([view], DURATION, 25_000);
    expect(store.advance(30_000)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(1.3);
  });

  it("새 phase는 서버 시작 위치로 뛰지 않고 현재 표시 위치에서 앞으로 이어간다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("DEPARTED")], DURATION, 0);
    store.advance(5_000);
    const previous = positionOf(store);

    store.update([viewFor("IN_TRANSIT")], DURATION, 5_000);
    expect(positionOf(store)).toBe(previous);
    store.advance(15_000);
    expect(positionOf(store)).toBeCloseTo((previous + 1.8) / 2);
    store.advance(25_000);
    expect(positionOf(store)).toBeCloseTo(1.8);
  });

  it("목표가 현재 표시 위치보다 뒤이면 300ms 보정 뒤 새 구간을 이동한다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(1_000);
    expect(positionOf(store)).toBeCloseTo(1.8);

    store.update([viewFor("DEPARTED")], DURATION, 1_000);
    expect(positionOf(store)).toBeCloseTo(1.8);
    store.advance(1_150);
    expect(positionOf(store)).toBeGreaterThan(1.08);
    expect(positionOf(store)).toBeLessThan(1.8);
    store.advance(1_300);
    expect(positionOf(store)).toBeCloseTo(1.08);
    store.advance(11_300);
    expect(positionOf(store)).toBeCloseTo(1.19);
    store.advance(21_300);
    expect(positionOf(store)).toBeCloseTo(1.3);
  });

  it("보정 중 새 응답이 와도 현재 위치와 최초 보정 종료 시각을 유지한다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(1_000);
    store.update([viewFor("DEPARTED")], DURATION, 1_000);
    store.advance(1_150);
    const previous = positionOf(store);
    const arriving = {
      ...viewFor("ARRIVING"),
      motionSegment: { fromStopSequence: 0, toStopSequence: 1, startProgress: 0.8, endProgress: 0.9 },
    };
    store.update([arriving], DURATION, 1_150);
    expect(positionOf(store)).toBe(previous);
    store.advance(1_300);
    expect(positionOf(store)).toBeCloseTo(0.8);
    store.advance(21_300);
    expect(positionOf(store)).toBeCloseTo(0.9);
  });

  it("reduced motion은 보정 중에도 최종 목표를 즉시 표시하고 다시 켜져도 재시작하지 않는다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(1_000);
    store.update([viewFor("DEPARTED")], DURATION, 1_000);
    store.setReducedMotion(true, 1_100);
    expect(positionOf(store)).toBeCloseTo(1.3);
    expect(store.advance(1_200)).toBe(false);

    store.setReducedMotion(false, 1_200);
    store.update([viewFor("DEPARTED")], DURATION, 1_200);
    expect(store.advance(1_300)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(1.3);
  });

  it("순번이 연속되지 않은 정류장 구간도 공통 좌표로 보간한다", () => {
    const store = createLiveVehicleMotionStore();
    const view = {
      ...viewFor("IN_TRANSIT"),
      motionSegment: { fromStopSequence: 1, toStopSequence: 3, startProgress: 0.2, endProgress: 0.8 },
    };
    store.update([view], DURATION, 0);
    expect(positionOf(store)).toBeCloseTo(1.4);
    store.advance(10_000);
    expect(positionOf(store)).toBeCloseTo(2);
    store.advance(20_000);
    expect(positionOf(store)).toBeCloseTo(2.6);
  });

  it("사라진 차량을 제거하고 구독 해제 뒤에는 통지하지 않는다", () => {
    const store = createLiveVehicleMotionStore();
    const listener = jest.fn();
    const unsubscribe = store.subscribe(listener);
    store.update([viewFor("DEPARTED")], DURATION, 0);
    expect(listener).toHaveBeenCalledTimes(1);
    const snapshot = store.frames;
    store.advance(0);
    expect(store.frames).toBe(snapshot);
    expect(listener).toHaveBeenCalledTimes(1);

    store.update([], DURATION, 1_000);
    expect(store.frames).toEqual([]);
    expect(store.advance(2_000)).toBe(false);
    expect(listener).toHaveBeenCalledTimes(2);
    unsubscribe();
    store.update([viewFor("DEPARTED")], DURATION, 3_000);
    store.clear();
    expect(listener).toHaveBeenCalledTimes(2);
    expect(store.frames).toEqual([]);
  });
});
