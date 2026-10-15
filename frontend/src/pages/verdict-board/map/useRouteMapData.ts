import { useEffect, useRef, useState } from "react";
import type { Board } from "@/shared/api/routeForecast.types";
import type { RouteMapData } from "./routeMapModel";

interface LoadedMapData {
  identity: string;
  data: RouteMapData;
}

export function useRouteMapData(board: Board): RouteMapData | null {
  const identity = JSON.stringify([
    board.route.id,
    board.route.referenceVersionId,
    board.route.turnSequence,
    board.stops.map(({ sequence, stopId, direction, name, boardingAllowed }) => [
      sequence,
      stopId,
      direction,
      name,
      boardingAllowed,
    ]),
  ]);
  const loadedIdentity = useRef<string | null>(null);
  const [loaded, setLoaded] = useState<LoadedMapData | null>(null);

  useEffect(() => {
    if (!__BOARD_MAP_PREVIEW__ || loadedIdentity.current === identity) return;
    let active = true;

    // 운영에서는 이 분기와 개발 fixture 청크를 제거한다. 실제 API 계약은 SAL-192에서 후속 연동한다.
    import("./routeMapPreview").then(
      ({ loadPreviewRouteMapData }) => {
        if (!active) return;
        const data = loadPreviewRouteMapData(board);
        loadedIdentity.current = identity;
        setLoaded(data === null ? null : { identity, data });
      },
      () => {
        if (!active) return;
        loadedIdentity.current = identity;
        setLoaded(null);
      },
    );

    return () => {
      active = false;
    };
  }, [board, identity]);

  return loaded?.identity === identity ? loaded.data : null;
}
