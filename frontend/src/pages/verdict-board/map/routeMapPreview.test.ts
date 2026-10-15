import { describe, expect, it } from "@jest/globals";
import { boardMock } from "@/shared/api/routeForecast.mock";
import { loadPreviewRouteMapData } from "./routeMapPreview";
import { previewRoutes } from "./routeMapPreviewStops";

describe("개발용 지도 fixture", () => {
  it("41개 노선의 모든 정류장 좌표를 담고 있다", () => {
    expect(Object.keys(previewRoutes)).toHaveLength(41);
    const stops = Object.values(previewRoutes).flatMap((route) => route.stops);
    expect(stops).toHaveLength(3215);
    expect(stops.every((stop) => Number.isFinite(stop[5]) && Number.isFinite(stop[6]))).toBe(true);
  });
  it("보드 mock과 같은 정류장 순번으로 연결하고 예측 갱신은 좌표를 재생성하지 않는다", () => {
    const data = loadPreviewRouteMapData(boardMock);
    expect(data?.stops).toHaveLength(boardMock.stops.length);
    expect(data?.routeId).toBe(boardMock.route.id);
    expect(data?.referenceVersionId).toBe(boardMock.route.referenceVersionId);
    expect(data?.paths.map((path) => path.direction)).toEqual(["UP", "DOWN"]);
    expect(loadPreviewRouteMapData({ ...boardMock, observedAt: "2026-10-14T09:00:00+09:00" })).toBe(data);
  });
  it("실제 노선의 정류장 구조가 바뀌면 과거 좌표를 붙이지 않는다", () => {
    expect(loadPreviewRouteMapData({ ...boardMock, stops: boardMock.stops.slice(1) })).toBeNull();
    expect(
      loadPreviewRouteMapData({
        ...boardMock,
        stops: boardMock.stops.map((stop, index) => (index === 0 ? { ...stop, stopId: "changed" } : stop)),
      }),
    ).toBeNull();
  });
  it("알려진 가상 E2E 노선만 짝을 맞춘 좌표를 만들고, 임의 노선은 대체하지 않는다", () => {
    const stops = boardMock.stops.slice(0, 4).map((stop, index) => ({ ...stop, stopId: `s${index}` }));
    expect(
      loadPreviewRouteMapData({ ...boardMock, route: { ...boardMock.route, id: "200000001" }, stops })?.stops.map(
        (stop) => stop.sequence,
      ),
    ).toEqual(stops.map((stop) => stop.sequence));
    expect(loadPreviewRouteMapData({ ...boardMock, route: { ...boardMock.route, id: "999999999" }, stops })).toBeNull();
  });
});
