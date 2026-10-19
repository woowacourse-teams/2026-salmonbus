import { describe, expect, it, jest } from "@jest/globals";
import { boardWith, liveVehiclesWith, stopAt, vehicleAt } from "@/testing/fixtures";
import type { Phase } from "@/shared/api/routeForecast.types";
import { liveVehicleViewsFor, type LiveVehicleView } from "../components/liveVehiclePolicy";
import { createLiveVehicleMotionStore, type LiveVehicleMotionStore } from "./useLiveVehicleMotion";

const DURATION = 20_000;

function viewFor(phase: Phase, sequence = 1, board = boardWith()): LiveVehicleView {
  return liveVehicleViewsFor(
    board,
    liveVehiclesWith([vehicleAt(sequence, { vehicleId: "moving-bus", phase, direction: "UP" })]),
    "UP",
  )[0]!;
}

function positionOf(store: LiveVehicleMotionStore): number {
  return store.frames[0]!.sequencePosition;
}

describe("shared live vehicle motion", () => {
  const sparseBoard = boardWith({
    stops: [10, 30, 60, 100, 150].map((sequence) => stopAt(sequence, { direction: "UP" })),
  });

  it("B ARRIVING에서 D ARRIVING으로 두 구간 앞서면 최신 구간 시작으로 보정한다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("ARRIVING", 30, sparseBoard)], DURATION, 0);
    store.advance(5_000);

    store.update([viewFor("ARRIVING", 100, sparseBoard)], DURATION, 5_000);
    expect(positionOf(store)).toBeCloseTo(92);
    store.advance(15_000);
    expect(positionOf(store)).toBeCloseTo(94);
    store.advance(25_000);
    expect(positionOf(store)).toBeCloseTo(96);
  });

  it("B ARRIVING에서 C ARRIVING으로 다음 구간이면 현재 위치에서 이어간다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("ARRIVING", 30, sparseBoard)], DURATION, 0);
    store.advance(5_000);
    const previous = positionOf(store);

    store.update([viewFor("ARRIVING", 60, sparseBoard)], DURATION, 5_000);
    expect(positionOf(store)).toBe(previous);
    store.advance(15_000);
    expect(positionOf(store)).toBeCloseTo((previous + 57) / 2);
  });

  it("A IN_TRANSIT과 B ARRIVING은 같은 구간으로 보고 이동을 이어간다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT", 10, sparseBoard)], DURATION, 0);
    store.advance(5_000);
    const previous = positionOf(store);

    store.update([viewFor("ARRIVING", 30, sparseBoard)], DURATION, 5_000);
    expect(positionOf(store)).toBe(previous);
    store.advance(15_000);
    expect(positionOf(store)).toBeCloseTo((previous + 28) / 2);
  });

  it("정류장 기준으로 두 칸 앞서도 phase를 반영해 다음 구간이면 이동을 이어간다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT", 10, sparseBoard)], DURATION, 0);
    store.advance(5_000);
    const previous = positionOf(store);

    store.update([viewFor("ARRIVING", 60, sparseBoard)], DURATION, 5_000);
    expect(positionOf(store)).toBe(previous);
  });

  it("두 구간보다 더 앞선 응답도 보정하고 뒤쪽 응답에서는 후진하지 않는다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("DEPARTED", 10, sparseBoard)], DURATION, 0);
    store.update([viewFor("ARRIVING", 150, sparseBoard)], DURATION, 5_000);
    expect(positionOf(store)).toBeCloseTo(140);

    store.update([viewFor("ARRIVING", 30, sparseBoard)], DURATION, 6_000);
    const previous = positionOf(store);
    store.update([viewFor("ARRIVING", 100, sparseBoard)], DURATION, 7_000);
    expect(positionOf(store)).toBe(previous);
    expect(store.advance(30_000)).toBe(false);
  });

  it("reduced motion에서는 두 구간 앞선 응답의 최종 위치를 바로 표시한다", () => {
    const store = createLiveVehicleMotionStore();
    store.setReducedMotion(true, 0);
    store.update([viewFor("ARRIVING", 30, sparseBoard)], DURATION, 0);
    store.update([viewFor("ARRIVING", 100, sparseBoard)], DURATION, 5_000);
    expect(positionOf(store)).toBeCloseTo(96);
    expect(store.advance(6_000)).toBe(false);
  });

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

  it("이동 중 뒤쪽 목표가 오면 현재 위치에서 멈추고 지도와 목록에 후진을 통지하지 않는다", () => {
    const store = createLiveVehicleMotionStore();
    const mapListener = jest.fn();
    const listListener = jest.fn();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(500);
    const previous = positionOf(store);
    expect(previous).toBeCloseTo(1.575);
    store.subscribe(mapListener);
    store.subscribe(listListener);

    store.update([viewFor("DEPARTED")], DURATION, 500);
    expect(store.advance(650)).toBe(false);
    expect(store.advance(800)).toBe(false);
    expect(store.advance(20_500)).toBe(false);
    expect(positionOf(store)).toBe(previous);
    expect(mapListener).not.toHaveBeenCalled();
    expect(listListener).not.toHaveBeenCalled();
  });

  it("멈춘 동안 같은 응답과 더 뒤쪽 응답이 반복돼도 현재 위치를 유지한다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(1_000);
    store.update([viewFor("DEPARTED")], DURATION, 1_000);
    const previous = positionOf(store);

    store.update([viewFor("DEPARTED")], DURATION * 4, 5_000);
    store.update([viewFor("ARRIVING")], DURATION, 10_000);
    expect(store.advance(30_000)).toBe(false);
    expect(positionOf(store)).toBe(previous);
  });

  it("멈춘 위치보다 앞선 목표가 오면 그 위치에서 선형 이동을 다시 시작한다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(500);
    const previous = positionOf(store);
    store.update([viewFor("DEPARTED")], DURATION, 500);

    // 처음 이동하던 phase로 돌아와도 멈춤 이후의 새 이동으로 받아들인다.
    store.update([viewFor("IN_TRANSIT")], DURATION, 5_000);
    expect(positionOf(store)).toBe(previous);
    expect(store.advance(15_000)).toBe(true);
    expect(positionOf(store)).toBeCloseTo((previous + 1.8) / 2);
    expect(store.advance(25_000)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(1.8);
  });

  it("현재 위치와 같은 목표는 멈춘 채 유지하고 다음 구간은 현재 위치에서 이어간다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(1_000);
    store.update([viewFor("DEPARTED")], DURATION, 1_000);

    store.update([viewFor("IN_TRANSIT")], DURATION, 2_000);
    expect(store.advance(22_000)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(1.8);

    store.update([viewFor("DEPARTED", 2)], DURATION, 22_000);
    expect(positionOf(store)).toBeCloseTo(1.8);
    expect(store.advance(32_000)).toBe(true);
    expect(positionOf(store)).toBeCloseTo(2.05);
    expect(store.advance(42_000)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(2.3);
  });

  it("멈춘 상태에서 reduced motion을 켜거나 뒤쪽 응답을 받아도 후진하지 않는다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], 1_000, 0);
    store.advance(1_000);
    store.update([viewFor("DEPARTED")], DURATION, 1_000);
    store.setReducedMotion(true, 1_100);
    expect(positionOf(store)).toBeCloseTo(1.8);
    expect(store.advance(1_200)).toBe(false);

    store.update([viewFor("ARRIVING")], DURATION, 1_200);
    expect(positionOf(store)).toBeCloseTo(1.8);
    store.update([viewFor("DEPARTED", 2)], DURATION, 1_300);
    expect(positionOf(store)).toBeCloseTo(2.3);

    store.setReducedMotion(false, 1_400);
    store.update([viewFor("DEPARTED", 2)], DURATION, 1_400);
    expect(store.advance(1_500)).toBe(false);
    expect(positionOf(store)).toBeCloseTo(2.3);
  });

  it("이동 중 reduced motion을 켜면 앞선 최종 목표를 즉시 표시한다", () => {
    const store = createLiveVehicleMotionStore();
    store.update([viewFor("IN_TRANSIT")], DURATION, 0);
    store.setReducedMotion(true, 5_000);
    expect(positionOf(store)).toBeCloseTo(1.8);
    expect(store.advance(10_000)).toBe(false);
  });

  it("순번이 연속되지 않은 정류장 구간도 공통 좌표로 보간한다", () => {
    const store = createLiveVehicleMotionStore();
    const view = {
      ...viewFor("IN_TRANSIT"),
      motionSegment: { segmentIndex: 0, fromStopSequence: 1, toStopSequence: 3, startProgress: 0.2, endProgress: 0.8 },
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
