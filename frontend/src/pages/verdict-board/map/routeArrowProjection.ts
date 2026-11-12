import type { NaverCoordinate, NaverMap, NaverMaps } from "@/shared/map/naverSdk";
import { ROUTE_ARROW_SPACING_PX } from "./routeMapGeometry";

type Point = { x: number; y: number };
type ProjectionView = Pick<NaverMap, "getSize" | "getOptions" | "getProjection" | "getTilt">;

export function createRouteArrowProjection(sdk: Pick<NaverMaps, "LatLng">, coordinates: readonly NaverCoordinate[]) {
  let cache:
    | {
        points: Float64Array;
        controls: NaverCoordinate[];
        reference: Point[];
        corners: { index: number; progress: number }[];
      }
    | null
    | undefined;
  const size = coordinates.length;
  function prepare() {
    const originX = coordinates[0]!.lng() / 360;
    const originY = -Math.log(Math.tan(Math.PI / 4 + (coordinates[0]!.lat() * Math.PI) / 360)) / (2 * Math.PI);
    const worldPoint = (c: NaverCoordinate) => ({
      x: c.lng() / 360 - originX,
      y: -Math.log(Math.tan(Math.PI / 4 + (c.lat() * Math.PI) / 360)) / (2 * Math.PI) - originY,
    });
    const points = new Float64Array(size * 2);
    let minLat = Infinity,
      maxLat = -Infinity,
      minLng = Infinity,
      maxLng = -Infinity;
    coordinates.forEach((c, i) => {
      const p = worldPoint(c);
      points[2 * i] = p.x;
      points[2 * i + 1] = p.y;
      minLat = Math.min(minLat, c.lat());
      maxLat = Math.max(maxLat, c.lat());
      minLng = Math.min(minLng, c.lng());
      maxLng = Math.max(maxLng, c.lng());
    });
    if (!points.every(Number.isFinite) || minLat < -85 || maxLat > 85 || maxLng - minLng > 180) {
      cache = null;
      return;
    }
    if (maxLat - minLat < 0.001) maxLat = minLat + 0.001;
    if (maxLng - minLng < 0.001) maxLng = Math.min(180, minLng + 0.001);
    const controls = [
      [0, 0],
      [1, 0],
      [0, 1],
      [1, 1],
      [0.5, 0.5],
      [0.5, 0],
      [0, 0.5],
      [1, 0.5],
      [0.5, 1],
    ].map(([x, y]) => new sdk.LatLng(minLat + (maxLat - minLat) * y!, minLng + (maxLng - minLng) * x!));
    const reference = controls.map((c) => worldPoint(c));
    const corners: { index: number; progress: number }[] = [];
    let progress = 0;
    for (let i = 1; i < size; i++) {
      const dx = points[2 * i]! - points[2 * (i - 1)]!,
        dy = points[2 * i + 1]! - points[2 * (i - 1) + 1]!;
      progress += Math.hypot(dx, dy);
      if (i + 1 < size) {
        const ex = points[2 * (i + 1)]! - points[2 * i]!,
          ey = points[2 * (i + 1) + 1]! - points[2 * i + 1]!;
        const angle = Math.abs(Math.atan2(dx * ey - dy * ex, dx * ex + dy * ey));
        if (angle > Math.PI / 18) corners.push({ index: i, progress });
      }
    }
    cache = { points, controls, reference, corners };
  }
  function frame(map: ProjectionView, allowPrepare = false) {
    const viewport = map.getSize();
    const projection = map.getProjection();
    const direct = (index: number) => projection.fromCoordToOffset(coordinates[index]!);
    const tilt = map.getTilt?.();
    if (
      !map.getOptions("gl") ||
      projection.getProjectionName?.() !== "EPSG:3857" ||
      tilt === undefined ||
      !Number.isFinite(tilt) ||
      Math.abs(tilt) > 1e-7 ||
      size < 2
    )
      return direct;
    if (cache === null) return direct;
    if (cache === undefined) {
      if (!allowPrepare) return direct;
      prepare();
      if (!cache) return direct;
    }
    const c = cache,
      q = c.controls.map((p) => projection.fromCoordToOffset(p)),
      r = c.reference;
    const rx = r[1]!.x - r[0]!.x,
      ry = r[1]!.y - r[0]!.y,
      sx = r[2]!.x - r[0]!.x,
      sy = r[2]!.y - r[0]!.y,
      det = rx * sy - ry * sx;
    const ux = q[1]!.x - q[0]!.x,
      uy = q[1]!.y - q[0]!.y,
      vx = q[2]!.x - q[0]!.x,
      vy = q[2]!.y - q[0]!.y;
    const a = (ux * sy - vx * ry) / det,
      b = (vx * rx - ux * sx) / det,
      d = (uy * sy - vy * ry) / det,
      e = (vy * rx - uy * sx) / det,
      tx = q[0]!.x - a * r[0]!.x - b * r[0]!.y,
      ty = q[0]!.y - d * r[0]!.x - e * r[0]!.y;
    const transform = (x: number, y: number) => ({ x: a * x + b * y + tx, y: d * x + e * y + ty });
    const error = Math.max(
      ...r.map((p, i) => {
        const predicted = transform(p.x, p.y);
        return Math.hypot(predicted.x - q[i]!.x, predicted.y - q[i]!.y);
      }),
    );
    const scale = Math.hypot(a, d);
    if (
      !Number.isFinite(error) ||
      error > 1e-5 ||
      !Number.isFinite(scale) ||
      scale <= 0 ||
      Math.abs(Math.hypot(b, e) / scale - 1) > 1e-7 ||
      Math.abs((a * b + d * e) / (scale * scale)) > 1e-7
    ) {
      return direct;
    }
    const margin = 4 * size * Math.max(error, 1e-7);
    for (const corner of c.corners) {
      const phase =
        (((corner.progress * scale - ROUTE_ARROW_SPACING_PX / 2) % ROUTE_ARROW_SPACING_PX) + ROUTE_ARROW_SPACING_PX) %
        ROUTE_ARROW_SPACING_PX;
      if (Math.min(phase, ROUTE_ARROW_SPACING_PX - phase) > margin) continue;
      const p = transform(c.points[2 * corner.index]!, c.points[2 * corner.index + 1]!);
      if (p.x >= -17 && p.x <= viewport.width + 17 && p.y >= -17 && p.y <= viewport.height + 17) {
        return direct;
      }
    }

    return (index: number) => transform(c.points[2 * index]!, c.points[2 * index + 1]!);
  }
  return {
    frame,
    clear() {
      cache = undefined;
    },
  };
}
