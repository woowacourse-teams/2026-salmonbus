import { describe, expect, it } from "@jest/globals";
import {
  draggedSheetHeight,
  halfSheetHeight,
  nextLargerSnap,
  quarterSheetHeight,
  settleSheet,
  sheetHeight,
  sheetPosition,
} from "./sheetPolicy";

const VIEWPORT = 844;
const HALF = 439;
const QUARTER = 220;

describe("halfSheetHeight", () => {
  it("뷰포트의 52%를 280~560px로 자르고 위로 120px을 남긴 뒤 정수 px로 반올림한다", () => {
    expect(halfSheetHeight(844)).toBe(HALF);
    expect(halfSheetHeight(553)).toBe(288);
    expect(halfSheetHeight(1400)).toBe(560);
    expect(halfSheetHeight(390)).toBe(270);
    expect(halfSheetHeight(256)).toBe(136);
  });
});

describe("quarterSheetHeight", () => {
  it("반 높이의 절반이되 216px보다 낮추지 않는다", () => {
    expect(quarterSheetHeight(844)).toBe(QUARTER);
    expect(quarterSheetHeight(664)).toBe(216);
  });

  it("반 높이가 216px보다 낮으면 반 높이와 같다", () => {
    expect(quarterSheetHeight(256)).toBe(136);
  });
});

describe("sheetHeight", () => {
  it("전체는 보이는 영역 높이, 반과 반의 반은 각 높이다", () => {
    expect(sheetHeight("full", VIEWPORT)).toBe(VIEWPORT);
    expect(sheetHeight("half", VIEWPORT)).toBe(HALF);
    expect(sheetHeight("quarter", VIEWPORT)).toBe(QUARTER);
  });

  it("보이는 영역이 줄면 그 높이로 다시 계산한다", () => {
    expect(sheetHeight("full", 544)).toBe(544);
    expect(sheetHeight("half", 544)).toBe(283);
  });
});

describe("sheetPosition", () => {
  it("닫혀 있으면 단계와 관계없이 접힘이고, 열려 있으면 그 단계다", () => {
    expect(sheetPosition({ open: false, snap: "full" })).toBe("collapsed");
    expect(sheetPosition({ open: true, snap: "quarter" })).toBe("quarter");
    expect(sheetPosition({ open: true, snap: "full" })).toBe("full");
  });
});

describe("nextLargerSnap", () => {
  it("반의 반 다음은 반, 반 다음은 전체, 전체 다음은 없다", () => {
    expect(nextLargerSnap("quarter")).toBe("half");
    expect(nextLargerSnap("half")).toBe("full");
    expect(nextLargerSnap("full")).toBeNull();
  });
});

describe("draggedSheetHeight", () => {
  it("손가락을 1:1로 따라간다", () => {
    expect(draggedSheetHeight(HALF, VIEWPORT, -100)).toBe(539);
    expect(draggedSheetHeight(HALF, VIEWPORT, 100)).toBe(339);
  });

  it("보이는 영역보다 위로는 1/4만, 최대 24px까지 따라간다", () => {
    expect(draggedSheetHeight(VIEWPORT, VIEWPORT, -40)).toBe(854);
    expect(draggedSheetHeight(VIEWPORT, VIEWPORT, -400)).toBe(868);
  });

  it("0보다 낮아지지 않는다", () => {
    expect(draggedSheetHeight(HALF, VIEWPORT, 1_000)).toBe(0);
  });
});

describe("settleSheet", () => {
  const release = (startHeight: number, height: number) =>
    settleSheet({ startHeight, height, viewportHeight: VIEWPORT });

  it("24px 넘게 끌면 끈 방향의 다음 단계로 간다", () => {
    expect(release(HALF, HALF + 30)).toEqual({ open: true, snap: "full" });
    expect(release(HALF, HALF - 30)).toEqual({ open: true, snap: "quarter" });
    expect(release(VIEWPORT, VIEWPORT - 30)).toEqual({ open: true, snap: "half" });
    expect(release(QUARTER, QUARTER + 30)).toEqual({ open: true, snap: "half" });
  });

  it("반의 반에서 24px 넘게 내리면 접힌다", () => {
    expect(release(QUARTER, QUARTER - 30)).toEqual({ open: false, snap: "quarter" });
  });

  it("24px보다 적게 끌면 출발 단계로 돌아간다", () => {
    expect(release(HALF, HALF + 20)).toEqual({ open: true, snap: "half" });
    expect(release(HALF, HALF - 20)).toEqual({ open: true, snap: "half" });
  });

  it("멀리 끌면 끈 방향의 단계 가운데 놓은 곳에서 가장 가까운 단계로 가고, 반대 방향으로는 가지 않는다", () => {
    expect(release(QUARTER, 700)).toEqual({ open: true, snap: "full" });
    expect(release(QUARTER, 470)).toEqual({ open: true, snap: "half" });
    expect(release(VIEWPORT, 300)).toEqual({ open: true, snap: "quarter" });
    expect(release(HALF, 180)).toEqual({ open: true, snap: "quarter" });
    expect(release(HALF, 100)).toEqual({ open: false, snap: "quarter" });
  });

  it("전체에서 더 올리면 전체에 머문다", () => {
    expect(release(VIEWPORT, VIEWPORT + 20)).toEqual({ open: true, snap: "full" });
  });

  it("반의 반이 반과 같은 작은 뷰포트에서는 반에서 내리면 접힌다", () => {
    expect(settleSheet({ startHeight: 136, height: 100, viewportHeight: 256 })).toEqual({ open: false, snap: "half" });
    expect(settleSheet({ startHeight: 136, height: 170, viewportHeight: 256 })).toEqual({ open: true, snap: "full" });
  });
});
