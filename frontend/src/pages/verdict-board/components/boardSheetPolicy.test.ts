import { describe, expect, it } from "@jest/globals";
import {
  adjacentBoardSheetSnapFor,
  boardSheetHeightsFor,
  boundedBoardSheetHeightFor,
  keyboardBoardSheetSnapFor,
  settledBoardSheetSnapFor,
} from "./boardSheetPolicy";

const HEIGHTS = boardSheetHeightsFor({ viewportHeight: 844, headerHeight: 190, safeTop: 44, safeBottom: 34 });

describe("boardSheetHeightsFor", () => {
  it("헤더와 하단 안전 영역을 최소 높이로, 화면 절반과 상단 안전 영역 아래를 다음 단계로 둔다", () => {
    expect(HEIGHTS).toEqual({ minimum: 224, half: 422, full: 800 });
  });

  it("헤더가 절반보다 높으면 최소와 중간을 같은 높이로 두고 전체를 넘기지 않는다", () => {
    expect(boardSheetHeightsFor({ viewportHeight: 300, headerHeight: 190, safeTop: 20, safeBottom: 34 })).toEqual({
      minimum: 224,
      half: 224,
      full: 280,
    });
    expect(boardSheetHeightsFor({ viewportHeight: 160, headerHeight: 190, safeTop: 20, safeBottom: 34 })).toEqual({
      minimum: 140,
      half: 140,
      full: 140,
    });
  });
});

describe("boundedBoardSheetHeightFor", () => {
  it("드래그로 헤더를 숨기거나 상단 안전 영역을 덮지 않는다", () => {
    expect(boundedBoardSheetHeightFor(0, HEIGHTS)).toBe(224);
    expect(boundedBoardSheetHeightFor(600, HEIGHTS)).toBe(600);
    expect(boundedBoardSheetHeightFor(1200, HEIGHTS)).toBe(800);
  });
});

describe("settledBoardSheetSnapFor", () => {
  it("느리게 놓으면 두 단계의 중간점을 기준으로 가까운 단계에 붙는다", () => {
    expect(settledBoardSheetSnapFor(322, 0, HEIGHTS, "half")).toBe("minimum");
    expect(settledBoardSheetSnapFor(324, 0, HEIGHTS, "minimum")).toBe("half");
    expect(settledBoardSheetSnapFor(610, 0, HEIGHTS, "full")).toBe("half");
    expect(settledBoardSheetSnapFor(612, 0, HEIGHTS, "half")).toBe("full");
  });

  it("짧게 밀어도 빠른 손가락 이동은 방향에 맞는 다음 단계로 투영한다", () => {
    expect(settledBoardSheetSnapFor(440, 1.2, HEIGHTS, "half")).toBe("full");
    expect(settledBoardSheetSnapFor(400, -0.6, HEIGHTS, "half")).toBe("minimum");
    expect(settledBoardSheetSnapFor(440, 0.44, HEIGHTS, "half")).toBe("half");
  });

  it("같은 높이의 단계와 정확한 중간점에서는 현재 선택을 우선한다", () => {
    expect(settledBoardSheetSnapFor(323, 0, HEIGHTS, "half")).toBe("half");
    const heights = { minimum: 224, half: 224, full: 280 };
    expect(settledBoardSheetSnapFor(224, 0, heights, "half")).toBe("half");
  });
});

describe("keyboardBoardSheetSnapFor", () => {
  it("화살표로 인접 높이, Home과 End로 양 끝을 선택한다", () => {
    expect(keyboardBoardSheetSnapFor("ArrowUp", "minimum", HEIGHTS)).toBe("half");
    expect(keyboardBoardSheetSnapFor("ArrowDown", "full", HEIGHTS)).toBe("half");
    expect(keyboardBoardSheetSnapFor("Home", "half", HEIGHTS)).toBe("minimum");
    expect(keyboardBoardSheetSnapFor("End", "minimum", HEIGHTS)).toBe("full");
    expect(keyboardBoardSheetSnapFor("Escape", "half", HEIGHTS)).toBeNull();
  });

  it("작은 화면에서 중복 높이는 건너뛰고 이동할 높이가 없으면 유지한다", () => {
    const heights = { minimum: 224, half: 224, full: 280 };
    expect(adjacentBoardSheetSnapFor("minimum", heights, "larger")).toBe("full");
    expect(adjacentBoardSheetSnapFor("half", heights, "larger")).toBe("full");
    expect(adjacentBoardSheetSnapFor("full", heights, "larger")).toBe("full");
    expect(adjacentBoardSheetSnapFor("full", { minimum: 140, half: 140, full: 140 }, "smaller")).toBe("full");
  });
});
