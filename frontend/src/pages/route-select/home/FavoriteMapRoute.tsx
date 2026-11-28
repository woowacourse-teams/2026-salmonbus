import { useEffect, useLayoutEffect, useMemo, useRef } from "react";
import { latestSuccessOf } from "@/shared/api/polledResource";
import type { Direction } from "@/shared/api/routeForecast.types";
import { fetchRouteGeometry } from "@/shared/api/routeGeometry.api";
import { usePolledRequest } from "@/shared/api/usePolledRequest";
import type { MapFlowSurface } from "@/shared/map/mapFlowSurface";
import { createNaverRouteOverview, type RouteMapAdapter } from "../../verdict-board/map/naverMapAdapter";
import { routeGeometryMapDataFor } from "../../verdict-board/map/routeGeometryMapData";
import { fullPathForDisplay } from "../../verdict-board/map/routeMapGeometry";
import { nextGeometryPollFrom, overviewPathsOf } from "./favoriteMapRoutes";
import type { Point } from "./homeMapData";

interface FavoriteMapRouteProps extends MapFlowSurface {
  routeId: string;
  direction: Direction;
  onPathsChange: (routeId: string, paths: Point[][] | undefined) => void;
}

export function useFavoriteMapGeometry(routeId: string) {
  const { resource: geometry } = usePolledRequest(
    (id, signal) => fetchRouteGeometry(id, { signal }),
    routeId,
    0,
    nextGeometryPollFrom,
  );
  const shape = latestSuccessOf(geometry)?.body;
  const data = useMemo(
    () =>
      shape && shape.reason !== "DISPLAY_WITHHELD"
        ? routeGeometryMapDataFor(shape, {
            id: routeId,
            referenceVersionId: shape.referenceVersionId,
          })
        : null,
    [shape, routeId],
  );
  const fullPath = useMemo(() => (data === null ? undefined : fullPathForDisplay(data)), [data]);
  return { data, fullPath, settled: geometry.status !== "idle" && geometry.status !== "pending" };
}

export function FavoriteMapRoute({ map, maps, element, routeId, direction, onPathsChange }: FavoriteMapRouteProps) {
  const { data, fullPath, settled: geometrySettled } = useFavoriteMapGeometry(routeId);
  const paths = useMemo(() => overviewPathsOf(data, fullPath), [data, fullPath]);
  const adapter = useRef<RouteMapAdapter | null>(null);

  useEffect(() => {
    if (geometrySettled) onPathsChange(routeId, paths);
  }, [geometrySettled, onPathsChange, paths, routeId]);
  useEffect(() => () => onPathsChange(routeId, undefined), [onPathsChange, routeId]);

  useLayoutEffect(() => {
    if (!data) return;
    const layer = createNaverRouteOverview({ map, maps, element }, data);
    adapter.current = layer;
    return () => {
      adapter.current = null;
      layer.destroy();
    };
  }, [map, maps, element, data]);

  useLayoutEffect(() => {
    adapter.current?.update(null, direction, []);
  }, [data, direction, element, map, maps]);

  return null;
}
