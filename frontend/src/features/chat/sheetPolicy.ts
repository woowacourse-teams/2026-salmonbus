export type SheetSnap = "half" | "full";
export type SheetPosition = "collapsed" | SheetSnap;

export interface SheetLayout {
  open: boolean;
  snap: SheetSnap;
}

export interface SheetRelease {
  startHeight: number;
  height: number;
  viewportHeight: number;
}

export const DRAG_START_DISTANCE_PX = 6;

const HALF_MIN_PX = 280;
const HALF_MAX_PX = 560;
const HALF_VIEWPORT_RATIO = 0.52;
const HALF_TOP_CLEARANCE_PX = 120;
const INTENT_DISTANCE_PX = 24;
const OVERDRAG_RATIO = 0.25;
const OVERDRAG_MAX_PX = 24;
const SNAPS_BOTTOM_UP: readonly SheetSnap[] = ["half", "full"];

export function halfSheetHeight(viewportHeight: number): number {
  const preferred = Math.min(Math.max(HALF_MIN_PX, viewportHeight * HALF_VIEWPORT_RATIO), HALF_MAX_PX);
  return Math.round(Math.max(0, Math.min(preferred, viewportHeight - HALF_TOP_CLEARANCE_PX)));
}

export function sheetHeight(snap: SheetSnap, viewportHeight: number): number {
  switch (snap) {
    case "full":
      return Math.round(viewportHeight);
    case "half":
      return halfSheetHeight(viewportHeight);
  }
}

export function sheetPosition({ open, snap }: SheetLayout): SheetPosition {
  return open ? snap : "collapsed";
}

export function nextLargerSnap(snap: SheetSnap): SheetSnap | null {
  return SNAPS_BOTTOM_UP[SNAPS_BOTTOM_UP.indexOf(snap) + 1] ?? null;
}

export function draggedSheetHeight(startHeight: number, viewportHeight: number, distanceY: number): number {
  const height = startHeight - distanceY;
  if (height <= viewportHeight) return Math.round(Math.max(height, 0));
  return Math.round(viewportHeight + Math.min(OVERDRAG_MAX_PX, (height - viewportHeight) * OVERDRAG_RATIO));
}

export function settleSheet({ startHeight, height, viewportHeight }: SheetRelease): SheetLayout {
  const stops = sheetStops(viewportHeight);
  const start = nearestStopIndex(stops, startHeight, stops.keys());
  const moved = height - startHeight;
  let index = start;
  if (Math.abs(moved) >= INTENT_DISTANCE_PX) {
    const ahead = [...stops.keys()].filter((candidate) => (moved > 0 ? candidate > start : candidate < start));
    if (ahead.length > 0) index = nearestStopIndex(stops, height, ahead);
  }
  const snap = stops[index]!.snap;
  return snap === null ? { open: false, snap: stops[1]!.snap! } : { open: true, snap };
}

interface SheetStop {
  snap: SheetSnap | null;
  height: number;
}

function sheetStops(viewportHeight: number): SheetStop[] {
  const opened = SNAPS_BOTTOM_UP.map((snap) => ({ snap, height: sheetHeight(snap, viewportHeight) }));
  return [{ snap: null, height: 0 }, ...opened];
}

function nearestStopIndex(stops: readonly SheetStop[], height: number, candidates: Iterable<number>): number {
  let nearest = -1;
  for (const index of candidates) {
    if (nearest < 0 || Math.abs(stops[index]!.height - height) < Math.abs(stops[nearest]!.height - height)) {
      nearest = index;
    }
  }
  return nearest;
}
