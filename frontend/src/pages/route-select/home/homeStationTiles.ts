import type { Point } from "./homeMapData";

export const MIN_STATION_ZOOM = 13;
const TILE_ZOOM = 14;
const TILE_COUNT = 2 ** TILE_ZOOM;

function tileY(latitude: number): number {
  const radians = (Math.max(-85.05112878, Math.min(85.05112878, latitude)) * Math.PI) / 180;
  return ((1 - Math.asinh(Math.tan(radians)) / Math.PI) / 2) * TILE_COUNT;
}

export function homeStationTileKey([west, south]: Point, [east, north]: Point): string {
  const left = Math.max(0, Math.floor(((west + 180) / 360) * TILE_COUNT));
  const right = Math.min(TILE_COUNT - 1, Math.floor(((east + 180) / 360) * TILE_COUNT));
  const top = Math.max(0, Math.floor(tileY(north)));
  const bottom = Math.min(TILE_COUNT - 1, Math.floor(tileY(south)));
  if ((right - left + 1) * (bottom - top + 1) > 64) return "";
  const tiles = [];
  for (let column = left; column <= right; column++) {
    for (let row = top; row <= bottom; row++) tiles.push(`${column}/${row}`);
  }
  return tiles.join(",");
}
