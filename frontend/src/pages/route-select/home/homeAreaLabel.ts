import { distanceMeters, type HomeStop, type Point } from "./homeMapData";

export function nearbyAreaLabel(point: Point, stops: HomeStop[]): string | null {
  let nearestDistance = 500;
  let label: string | null = null;
  for (const stop of stops) {
    const distance = distanceMeters(point, stop.point);
    if (distance < nearestDistance) {
      nearestDistance = distance;
      label = `${stop.name} 주변`;
    }
  }
  return label;
}

const TILE_DEGREES = 0.25;

export async function lookupAreaLabel(point: Point): Promise<string | null> {
  try {
    // shortcut: 수도권 밖은 시·군·구까지 표시한다. 읍·면·동이 필요해지면 해당 권역의 경계를 포함한다.
    const [longitude, latitude] = point;
    const tileName = `${Math.floor(longitude / TILE_DEGREES)}_${Math.floor(latitude / TILE_DEGREES)}`;
    const { loadAreaTile } = await import("./homeAreaTiles");
    const tile = await loadAreaTile(tileName);
    const area = tile.areas.find(([, polygons]) =>
      polygons.some(
        ([outer, ...holes]) =>
          outer &&
          contains(point, ringFrom(outer, tile.scale)) &&
          !holes.some((hole) => contains(point, ringFrom(hole, tile.scale))),
      ),
    );
    return area?.[0] ?? null;
  } catch {
    return null;
  }
}

function ringFrom(deltas: number[], scale: number): number[][] {
  const ring: number[][] = [];
  let x = 0;
  let y = 0;
  for (let index = 0; index < deltas.length; index += 2) {
    x += deltas[index]!;
    y += deltas[index + 1]!;
    ring.push([x / scale, y / scale]);
  }
  if (ring[0]) ring.push(ring[0]);
  return ring;
}

function contains([x, y]: Point, ring: number[][]): boolean {
  let inside = false;
  // 닫힌 경계에서 점의 오른쪽으로 뻗은 선이 경계를 홀수 번 지나면 내부다.
  for (let index = 1; index < ring.length; index++) {
    const [ax, ay] = ring[index - 1]!;
    const [bx, by] = ring[index]!;
    if (ax === undefined || ay === undefined || bx === undefined || by === undefined) continue;
    if (ay > y !== by > y && x < ((bx - ax) * (y - ay)) / (by - ay) + ax) inside = !inside;
  }
  return inside;
}
