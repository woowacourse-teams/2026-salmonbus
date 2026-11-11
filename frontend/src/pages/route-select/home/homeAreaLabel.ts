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

export async function lookupAreaLabel(point: Point): Promise<string | null> {
  try {
    // shortcut: 수도권 밖은 시·군·구까지 표시한다. 읍·면·동이 필요해지면 해당 권역의 경계를 포함한다.
    const { default: areas } = await import("./data/adminAreas.json");
    const [longitude, latitude] = point;
    const area = areas.find(
      ({ west, south, east, north, polygons }) =>
        west <= longitude &&
        longitude <= east &&
        south <= latitude &&
        latitude <= north &&
        polygons.some(
          ([outer, ...holes]) => outer && contains(point, outer) && !holes.some((hole) => contains(point, hole)),
        ),
    );
    return area?.label ?? null;
  } catch {
    return null;
  }
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
