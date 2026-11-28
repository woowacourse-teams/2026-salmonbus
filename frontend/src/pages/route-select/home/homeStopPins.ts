import { MIN_STATION_ZOOM } from "./homeStationTiles";

const MARKER_MARGIN = 16;
const PIN_SPACING_ZOOM = 15;
const THINNED_PIN_SPACING = 36;

interface ScreenPoint {
  x: number;
  y: number;
}

export interface HomeStopScreen {
  zoom: number;
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * 화면에 둘 정류장 핀과 옆으로 밀 거리를 고른다.
 * 축소한 화면에서는 먼저 놓인 핀과 가까운 정류장을 빼고,
 * 확대한 화면에서는 빼지 않고 겹치는 핀을 오른쪽이나 왼쪽으로 핀 하나(pinClearance)만큼 민다.
 */
export function homeStopPinsFor<Stop>(
  stops: readonly Stop[],
  screenPointOf: (stop: Stop) => ScreenPoint,
  screen: HomeStopScreen,
  pinClearance: number,
): { stop: Stop; shift: number }[] {
  const { zoom, left, top, width, height } = screen;
  if (zoom < MIN_STATION_ZOOM) return [];

  const spacing = zoom < PIN_SPACING_ZOOM ? THINNED_PIN_SPACING : 0;
  const kept: ScreenPoint[] = [];
  const pins: { stop: Stop; shift: number }[] = [];
  for (const stop of stops) {
    const { x, y } = screenPointOf(stop);
    if (
      x < left - MARKER_MARGIN ||
      y < top - MARKER_MARGIN ||
      x > left + width + MARKER_MARGIN ||
      y > top + height + MARKER_MARGIN
    )
      continue;
    if (spacing > 0 && kept.some((point) => Math.hypot(point.x - x, point.y - y) < spacing)) continue;

    const pinX =
      spacing > 0
        ? x
        : ([x, x + pinClearance, x - pinClearance].find(
            (candidate) =>
              !kept.some(
                (point) => Math.abs(point.x - candidate) < pinClearance && Math.abs(point.y - y) < pinClearance,
              ),
          ) ?? x);
    kept.push({ x: pinX, y });
    pins.push({ stop, shift: pinX - x });
  }
  return pins;
}
