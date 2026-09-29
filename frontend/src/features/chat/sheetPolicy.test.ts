import { describe, expect, it } from "@jest/globals";
import {
  draggedSheetOffset,
  halfSheetHeight,
  releaseVelocity,
  sheetOffset,
  sheetPositionAfterRelease,
} from "./sheetPolicy";

const VIEWPORT = 844;

describe("halfSheetHeight", () => {
  it("뷰포트의 52%를 280~560px로 자르고 위로 120px을 남긴다", () => {
    expect(halfSheetHeight(844)).toBeCloseTo(438.88);
    expect(halfSheetHeight(553)).toBeCloseTo(287.56);
    expect(halfSheetHeight(1400)).toBe(560);
    expect(halfSheetHeight(390)).toBe(270);
    expect(halfSheetHeight(256)).toBe(136);
  });
});

describe("sheetOffset", () => {
  it("전체는 0, 절반은 뷰포트에서 절반 높이를 뺀 값, 접힘은 뷰포트 높이만큼 내린다", () => {
    expect(sheetOffset("full", VIEWPORT)).toBe(0);
    expect(sheetOffset("half", VIEWPORT)).toBeCloseTo(405.12);
    expect(sheetOffset("collapsed", VIEWPORT)).toBe(VIEWPORT);
  });
});

describe("draggedSheetOffset", () => {
  it("손가락을 그대로 따라간다", () => {
    expect(draggedSheetOffset("half", VIEWPORT, -100)).toBeCloseTo(305.12);
    expect(draggedSheetOffset("full", VIEWPORT, 120)).toBe(120);
  });

  it("전체보다 위로는 1/4만, 최대 24px까지 따라간다", () => {
    expect(draggedSheetOffset("full", VIEWPORT, -40)).toBe(-10);
    expect(draggedSheetOffset("full", VIEWPORT, -400)).toBe(-24);
  });

  it("뷰포트 아래로는 더 내려가지 않는다", () => {
    expect(draggedSheetOffset("half", VIEWPORT, 1_000)).toBe(VIEWPORT);
  });
});

describe("releaseVelocity", () => {
  it("놓기 전 100ms 안의 움직임으로 속도를 구한다", () => {
    // given
    const samples = [
      { time: 0, y: 500 },
      { time: 50, y: 480 },
      { time: 120, y: 440 },
    ];

    // when
    const velocity = releaseVelocity(samples, { time: 150, y: 430 });

    // then
    expect(velocity).toBeCloseTo(-0.5);
  });

  it("100ms 넘게 멈췄다 놓으면 속도는 0이다", () => {
    expect(releaseVelocity([{ time: 0, y: 500 }], { time: 300, y: 400 })).toBe(0);
  });
});

describe("sheetPositionAfterRelease", () => {
  it("빠르게 튕기면 그 방향으로 한 단계만 간다", () => {
    expect(sheetPositionAfterRelease({ from: "half", viewportHeight: VIEWPORT, distanceY: -40, velocityY: -0.5 })).toBe(
      "full",
    );
    expect(sheetPositionAfterRelease({ from: "half", viewportHeight: VIEWPORT, distanceY: 40, velocityY: 0.5 })).toBe(
      "collapsed",
    );
    expect(sheetPositionAfterRelease({ from: "full", viewportHeight: VIEWPORT, distanceY: 100, velocityY: 0.9 })).toBe(
      "half",
    );
    expect(sheetPositionAfterRelease({ from: "full", viewportHeight: VIEWPORT, distanceY: -10, velocityY: -0.9 })).toBe(
      "full",
    );
  });

  it("속도가 0.4px/ms 이하면 중간선 기준 가까운 단계로 간다", () => {
    expect(sheetPositionAfterRelease({ from: "half", viewportHeight: VIEWPORT, distanceY: -150, velocityY: 0.4 })).toBe(
      "half",
    );
    expect(
      sheetPositionAfterRelease({ from: "half", viewportHeight: VIEWPORT, distanceY: -250, velocityY: -0.1 }),
    ).toBe("full");
    expect(sheetPositionAfterRelease({ from: "half", viewportHeight: VIEWPORT, distanceY: 150, velocityY: 0.1 })).toBe(
      "half",
    );
    expect(sheetPositionAfterRelease({ from: "half", viewportHeight: VIEWPORT, distanceY: 250, velocityY: 0 })).toBe(
      "collapsed",
    );
  });

  it("빠르더라도 뷰포트의 40% 이상 움직였으면 가까운 단계로 간다", () => {
    expect(sheetPositionAfterRelease({ from: "full", viewportHeight: VIEWPORT, distanceY: 700, velocityY: 2 })).toBe(
      "collapsed",
    );
    expect(sheetPositionAfterRelease({ from: "full", viewportHeight: VIEWPORT, distanceY: 350, velocityY: 2 })).toBe(
      "half",
    );
  });
});
