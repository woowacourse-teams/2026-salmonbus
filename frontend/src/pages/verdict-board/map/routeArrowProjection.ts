import type { NaverCoordinate, NaverMap, NaverMaps } from "@/shared/map/naverSdk";
import { ROUTE_ARROW_SPACING_PX, ROUTE_ARROW_VIEWPORT_MARGIN_PX } from "./routeMapGeometry";

type Point = { x: number; y: number };
type ProjectionView = Pick<NaverMap, "getSize" | "getOptions" | "getProjection" | "getTilt">;
type Projection = ReturnType<NaverMap["getProjection"]>;

// 경로를 둘러싼 영역의 네 모서리, 중심, 네 변의 중점에 제어점을 둔다.
const CONTROL_POSITIONS = [
  [0, 0],
  [1, 0],
  [0, 1],
  [1, 1],
  [0.5, 0.5],
  [0.5, 0],
  [0, 0.5],
  [1, 0.5],
  [0.5, 1],
] as const;
const MERCATOR_LATITUDE_LIMIT = 85;
const MAX_LONGITUDE_SPAN = 180;
// 경로가 거의 한 점이어도 제어점끼리 겹치지 않게 하는 최소 폭(도)
const MIN_CONTROL_SPAN_DEGREES = 0.001;
const FLAT_TILT_TOLERANCE = 1e-7;
const CONTROL_FIT_TOLERANCE_PX = 1e-5;
const SIMILARITY_TOLERANCE = 1e-7;
const MIN_FIT_ERROR_PX = 1e-7;
// 선분마다 양 끝점의 오차가 누적 거리에 더해질 수 있어 점 수에 비례하는 여유를 둔다.
const CUMULATIVE_ERROR_FACTOR = 4;
// 이보다 크게 꺾이는 점에 화살표가 놓이면 작은 거리 오차로도 화살표가 다른 선분을 고를 수 있다.
const SHARP_TURN_RADIANS = Math.PI / 18;
const SHARP_TURN_VIEWPORT_MARGIN_PX = ROUTE_ARROW_VIEWPORT_MARGIN_PX + 1;

interface WorldRoute {
  // 첫 점을 원점으로 옮긴 Web Mercator 정규화 좌표를 x, y 순서로 담는다.
  points: Float64Array;
  controls: NaverCoordinate[];
  controlPoints: Point[];
  sharpTurns: { index: number; distance: number }[];
}
type WorldRouteState = { status: "unprepared" } | { status: "unsupported" } | { status: "ready"; route: WorldRoute };
interface Similarity {
  apply: (x: number, y: number) => Point;
  scale: number;
  fitError: number;
}

function mercatorPoint(coordinate: NaverCoordinate): Point {
  return {
    x: coordinate.lng() / 360,
    y: -Math.log(Math.tan(Math.PI / 4 + (coordinate.lat() * Math.PI) / 360)) / (2 * Math.PI),
  };
}

// GL 지도의 EPSG:3857 평면 투영이고 기울기가 없을 때만 SDK 투영이 확대·회전·이동으로 표현된다.
function supportsWorldReuse(map: ProjectionView, projection: Projection) {
  const tilt = map.getTilt?.();
  return (
    map.getOptions("gl") &&
    projection.getProjectionName?.() === "EPSG:3857" &&
    tilt !== undefined &&
    Number.isFinite(tilt) &&
    Math.abs(tilt) <= FLAT_TILT_TOLERANCE
  );
}

function prepareWorldRoute(sdk: Pick<NaverMaps, "LatLng">, coordinates: readonly NaverCoordinate[]): WorldRoute | null {
  const origin = mercatorPoint(coordinates[0]!);
  const toWorld = (coordinate: NaverCoordinate) => {
    const point = mercatorPoint(coordinate);
    return { x: point.x - origin.x, y: point.y - origin.y };
  };
  const points = new Float64Array(coordinates.length * 2);
  let minLat = Infinity;
  let maxLat = -Infinity;
  let minLng = Infinity;
  let maxLng = -Infinity;
  coordinates.forEach((coordinate, index) => {
    const point = toWorld(coordinate);
    points[2 * index] = point.x;
    points[2 * index + 1] = point.y;
    minLat = Math.min(minLat, coordinate.lat());
    maxLat = Math.max(maxLat, coordinate.lat());
    minLng = Math.min(minLng, coordinate.lng());
    maxLng = Math.max(maxLng, coordinate.lng());
  });
  if (
    !points.every(Number.isFinite) ||
    minLat < -MERCATOR_LATITUDE_LIMIT ||
    maxLat > MERCATOR_LATITUDE_LIMIT ||
    maxLng - minLng > MAX_LONGITUDE_SPAN
  )
    return null;
  if (maxLat - minLat < MIN_CONTROL_SPAN_DEGREES) maxLat = minLat + MIN_CONTROL_SPAN_DEGREES;
  if (maxLng - minLng < MIN_CONTROL_SPAN_DEGREES) maxLng = Math.min(180, minLng + MIN_CONTROL_SPAN_DEGREES);
  const controls = CONTROL_POSITIONS.map(
    ([x, y]) => new sdk.LatLng(minLat + (maxLat - minLat) * y, minLng + (maxLng - minLng) * x),
  );
  return { points, controls, controlPoints: controls.map(toWorld), sharpTurns: sharpTurnsOf(points) };
}

function sharpTurnsOf(points: Float64Array) {
  const sharpTurns: { index: number; distance: number }[] = [];
  const count = points.length / 2;
  let distance = 0;
  for (let index = 1; index < count; index += 1) {
    const inX = points[2 * index]! - points[2 * (index - 1)]!;
    const inY = points[2 * index + 1]! - points[2 * (index - 1) + 1]!;
    distance += Math.hypot(inX, inY);
    if (index + 1 >= count) continue;
    const outX = points[2 * (index + 1)]! - points[2 * index]!;
    const outY = points[2 * (index + 1) + 1]! - points[2 * index + 1]!;
    const turn = Math.abs(Math.atan2(inX * outY - inY * outX, inX * outX + inY * outY));
    if (turn > SHARP_TURN_RADIANS) sharpTurns.push({ index, distance });
  }
  return sharpTurns;
}

// 세 제어점의 세계 좌표와 화면 좌표로 2차원 변환을 구한다.
function affineFromThreeControls(world: readonly Point[], screen: readonly Point[]) {
  const w0 = world[0]!;
  const w1 = world[1]!;
  const w2 = world[2]!;
  const s0 = screen[0]!;
  const s1 = screen[1]!;
  const s2 = screen[2]!;
  const worldU = { x: w1.x - w0.x, y: w1.y - w0.y };
  const worldV = { x: w2.x - w0.x, y: w2.y - w0.y };
  const screenU = { x: s1.x - s0.x, y: s1.y - s0.y };
  const screenV = { x: s2.x - s0.x, y: s2.y - s0.y };
  const determinant = worldU.x * worldV.y - worldU.y * worldV.x;
  const xx = (screenU.x * worldV.y - screenV.x * worldU.y) / determinant;
  const xy = (screenV.x * worldU.x - screenU.x * worldV.x) / determinant;
  const yx = (screenU.y * worldV.y - screenV.y * worldU.y) / determinant;
  const yy = (screenV.y * worldU.x - screenU.y * worldV.x) / determinant;
  const offsetX = s0.x - xx * w0.x - xy * w0.y;
  const offsetY = s0.y - yx * w0.x - yy * w0.y;
  return {
    xx,
    xy,
    yx,
    yy,
    apply: (x: number, y: number) => ({ x: xx * x + xy * y + offsetX, y: yx * x + yy * y + offsetY }),
  };
}

// 나머지 제어점까지 맞고, 두 축이 같은 배율로 서로 수직이면 확대·회전·이동만으로 이루어진 변환으로 본다.
function similarityFromControls(world: readonly Point[], screen: readonly Point[]): Similarity | null {
  const affine = affineFromThreeControls(world, screen);
  const fitError = Math.max(
    ...world.map((point, index) => {
      const predicted = affine.apply(point.x, point.y);
      return Math.hypot(predicted.x - screen[index]!.x, predicted.y - screen[index]!.y);
    }),
  );
  const scale = Math.hypot(affine.xx, affine.yx);
  const equalScale = Math.abs(Math.hypot(affine.xy, affine.yy) / scale - 1) <= SIMILARITY_TOLERANCE;
  const orthogonal =
    Math.abs((affine.xx * affine.xy + affine.yx * affine.yy) / (scale * scale)) <= SIMILARITY_TOLERANCE;
  if (!Number.isFinite(fitError) || fitError > CONTROL_FIT_TOLERANCE_PX) return null;
  if (!Number.isFinite(scale) || scale <= 0 || !equalScale || !orthogonal) return null;
  return { apply: affine.apply, scale, fitError };
}

// 화면 근처의 급꺾임에 화살표 간격의 경계가 오차 범위 안으로 겹치면 직접 투영과 다른 선분을 고를 수 있다.
function hasAmbiguousSharpTurn(route: WorldRoute, similarity: Similarity, viewport: { width: number; height: number }) {
  const spacing = ROUTE_ARROW_SPACING_PX;
  const margin = SHARP_TURN_VIEWPORT_MARGIN_PX;
  const tolerance =
    CUMULATIVE_ERROR_FACTOR * (route.points.length / 2) * Math.max(similarity.fitError, MIN_FIT_ERROR_PX);
  return route.sharpTurns.some(({ index, distance }) => {
    const phase = (((distance * similarity.scale - spacing / 2) % spacing) + spacing) % spacing;
    if (Math.min(phase, spacing - phase) > tolerance) return false;
    const point = similarity.apply(route.points[2 * index]!, route.points[2 * index + 1]!);
    return (
      point.x >= -margin &&
      point.x <= viewport.width + margin &&
      point.y >= -margin &&
      point.y <= viewport.height + margin
    );
  });
}

/**
 * 노선 점의 세계 좌표를 한 번 계산해 두고, 카메라가 바뀌면 제어점만 SDK로 투영해 화면 좌표를 구한다.
 * 재사용 조건을 만족하지 못하면 SDK로 각 점을 직접 투영한다.
 */
export function createRouteArrowProjection(sdk: Pick<NaverMaps, "LatLng">, coordinates: readonly NaverCoordinate[]) {
  let state: WorldRouteState = { status: "unprepared" };

  function frame(map: ProjectionView) {
    const projection = map.getProjection();
    const direct = (index: number) => projection.fromCoordToOffset(coordinates[index]!);
    if (coordinates.length < 2 || !supportsWorldReuse(map, projection)) return direct;
    if (state.status === "unprepared") {
      const route = prepareWorldRoute(sdk, coordinates);
      state = route === null ? { status: "unsupported" } : { status: "ready", route };
    }
    if (state.status !== "ready") return direct;
    const { route } = state;
    const similarity = similarityFromControls(
      route.controlPoints,
      route.controls.map((control) => projection.fromCoordToOffset(control)),
    );
    if (similarity === null || hasAmbiguousSharpTurn(route, similarity, map.getSize())) return direct;
    return (index: number) => similarity.apply(route.points[2 * index]!, route.points[2 * index + 1]!);
  }

  return {
    frame,
    clear() {
      state = { status: "unprepared" };
    },
  };
}
