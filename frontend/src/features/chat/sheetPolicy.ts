export type SheetPosition = "collapsed" | "half" | "full";
export type OpenSheetPosition = Exclude<SheetPosition, "collapsed">;

export interface PointerSample {
  time: number;
  y: number;
}

export interface SheetRelease {
  from: OpenSheetPosition;
  viewportHeight: number;
  distanceY: number;
  velocityY: number;
}

export const DRAG_START_DISTANCE_PX = 6;
export const VELOCITY_WINDOW_MS = 100;

const HALF_MIN_PX = 280;
const HALF_MAX_PX = 560;
const HALF_VIEWPORT_RATIO = 0.52;
const HALF_TOP_CLEARANCE_PX = 120;
const OVERDRAG_RATIO = 0.25;
const OVERDRAG_MAX_PX = 24;
const FLICK_VELOCITY_PX_PER_MS = 0.4;
const FLICK_MAX_DISTANCE_RATIO = 0.4;
const POSITIONS_TOP_DOWN: readonly SheetPosition[] = ["full", "half", "collapsed"];

export function halfSheetHeight(viewportHeight: number): number {
  const preferred = Math.min(Math.max(HALF_MIN_PX, viewportHeight * HALF_VIEWPORT_RATIO), HALF_MAX_PX);
  return Math.max(0, Math.min(preferred, viewportHeight - HALF_TOP_CLEARANCE_PX));
}

export function sheetOffset(position: SheetPosition, viewportHeight: number): number {
  switch (position) {
    case "full":
      return 0;
    case "half":
      return viewportHeight - halfSheetHeight(viewportHeight);
    case "collapsed":
      return viewportHeight;
  }
}

export function draggedSheetOffset(from: OpenSheetPosition, viewportHeight: number, distanceY: number): number {
  const offset = sheetOffset(from, viewportHeight) + distanceY;
  if (offset < 0) return -Math.min(OVERDRAG_MAX_PX, -offset * OVERDRAG_RATIO);
  return Math.min(offset, viewportHeight);
}

export function releaseVelocity(samples: readonly PointerSample[], release: PointerSample): number {
  const first = samples.find((sample) => release.time - sample.time <= VELOCITY_WINDOW_MS);
  if (first === undefined) return 0;
  const elapsed = release.time - first.time;
  return elapsed > 0 ? (release.y - first.y) / elapsed : 0;
}

export function sheetPositionAfterRelease({ from, viewportHeight, distanceY, velocityY }: SheetRelease): SheetPosition {
  const flicked =
    Math.abs(velocityY) > FLICK_VELOCITY_PX_PER_MS && Math.abs(distanceY) < viewportHeight * FLICK_MAX_DISTANCE_RATIO;
  if (flicked) {
    const index = POSITIONS_TOP_DOWN.indexOf(from) + (velocityY > 0 ? 1 : -1);
    return POSITIONS_TOP_DOWN[Math.min(Math.max(index, 0), POSITIONS_TOP_DOWN.length - 1)] ?? from;
  }

  const offset = Math.min(Math.max(sheetOffset(from, viewportHeight) + distanceY, 0), viewportHeight);
  let nearest: SheetPosition = from;
  for (const position of POSITIONS_TOP_DOWN) {
    const distance = Math.abs(sheetOffset(position, viewportHeight) - offset);
    if (distance < Math.abs(sheetOffset(nearest, viewportHeight) - offset)) nearest = position;
  }
  return nearest;
}
