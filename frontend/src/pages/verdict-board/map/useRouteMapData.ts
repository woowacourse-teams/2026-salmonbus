import { useEffect, useState } from "react";
import type { Board } from "@/shared/api/routeForecast.types";
import { fetchRouteGeometry } from "@/shared/api/routeGeometry.api";
import { routeGeometryMapDataFor } from "./routeGeometryMapData";
import type { RouteMapData } from "./routeMapModel";

interface LoadedMapData {
  identity: string;
  data: RouteMapData | null;
}

export function useRouteMapData(board: Board): RouteMapData | null {
  const { id: routeId, referenceVersionId } = board.route;
  const identity = JSON.stringify([routeId, referenceVersionId]);
  const [loaded, setLoaded] = useState<LoadedMapData | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    let active = true;

    void fetchRouteGeometry(routeId, { signal: controller.signal, referenceVersionId }).then((result) => {
      if (!active) return;
      const data = result.ok ? routeGeometryMapDataFor(result.body, { id: routeId, referenceVersionId }) : null;
      setLoaded({ identity, data });
    });

    return () => {
      active = false;
      controller.abort();
    };
  }, [identity, routeId, referenceVersionId]);

  return loaded?.identity === identity ? loaded.data : null;
}
