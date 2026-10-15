export type BoardSheetSnap = "minimum" | "half" | "expanded" | "full";

export type BoardSheetHeights = Record<BoardSheetSnap, number>;

export interface BoardSheetMeasurements {
  viewportHeight: number;
  headerHeight: number;
  safeTop: number;
  safeBottom: number;
}

const SNAPS: readonly BoardSheetSnap[] = ["minimum", "half", "expanded", "full"];
const FLICK_SPEED_PX_PER_MS = 0.45;
const FLICK_PROJECTION_MS = 150;

export const BOARD_SHEET_DRAG_THRESHOLD = 6;

export function boardSheetHeightsFor({
  viewportHeight,
  headerHeight,
  safeTop,
  safeBottom,
}: BoardSheetMeasurements): BoardSheetHeights {
  const full = Math.max(0, viewportHeight - safeTop);
  const minimum = Math.min(full, Math.max(0, headerHeight + safeBottom));
  return {
    minimum,
    half: Math.min(full, Math.max(minimum, viewportHeight / 2)),
    expanded: Math.min(full, Math.max(minimum, viewportHeight * 0.7)),
    full,
  };
}

export function boundedBoardSheetHeightFor(height: number, heights: BoardSheetHeights): number {
  return Math.min(heights.full, Math.max(heights.minimum, height));
}

export function adjacentBoardSheetSnapFor(
  snap: BoardSheetSnap,
  heights: BoardSheetHeights,
  direction: "larger" | "smaller",
): BoardSheetSnap {
  const candidates = direction === "larger" ? SNAPS : [...SNAPS].reverse();
  return (
    candidates.find((candidate) =>
      direction === "larger" ? heights[candidate] > heights[snap] : heights[candidate] < heights[snap],
    ) ?? snap
  );
}

export function settledBoardSheetSnapFor(
  height: number,
  velocity: number,
  heights: BoardSheetHeights,
  currentSnap: BoardSheetSnap,
): BoardSheetSnap {
  const projected = height + (Math.abs(velocity) >= FLICK_SPEED_PX_PER_MS ? velocity * FLICK_PROJECTION_MS : 0);
  // 같은 높이가 된 단계는 현재 선택을 유지해 작은 화면에서 단계가 저절로 바뀌지 않게 한다.
  return SNAPS.reduce(
    (nearest, candidate) =>
      Math.abs(heights[candidate] - projected) < Math.abs(heights[nearest] - projected) ? candidate : nearest,
    currentSnap,
  );
}

export function keyboardBoardSheetSnapFor(
  key: string,
  snap: BoardSheetSnap,
  heights: BoardSheetHeights,
): BoardSheetSnap | null {
  switch (key) {
    case "ArrowUp":
    case "ArrowRight":
      return adjacentBoardSheetSnapFor(snap, heights, "larger");
    case "ArrowDown":
    case "ArrowLeft":
      return adjacentBoardSheetSnapFor(snap, heights, "smaller");
    case "Home":
      return "minimum";
    case "End":
      return "full";
    default:
      return null;
  }
}
