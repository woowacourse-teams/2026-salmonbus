import { useEffect, useMemo, useRef, useState } from "react";
import { fetchStationTile, type StationTileResult } from "@/shared/api/stationMap.api";
import type { HomeStop } from "./homeMapData";

export function useHomeStations(tileKey: string, fallback: HomeStop[]) {
  const tiles = useMemo(() => (tileKey ? tileKey.split(",") : []), [tileKey]);
  const [catalogUnavailable, setCatalogUnavailable] = useState(false);
  const [results, setResults] = useState<Record<string, StationTileResult>>({});
  const [failedScope, setFailedScope] = useState<string[] | null>(null);
  const retryFailed = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (tiles.length === 0 || catalogUnavailable) return;
    const controller = new AbortController();
    const pending = new Set(tiles);
    const failed = new Set<string>();
    const timers = new Set<number>();

    function clearTimers() {
      timers.forEach((timer) => window.clearTimeout(timer));
      timers.clear();
    }

    async function load(tile: string) {
      if (controller.signal.aborted || document.visibilityState !== "visible" || !pending.delete(tile)) return;
      const result = await fetchStationTile(tile, controller.signal);
      if (controller.signal.aborted) return;
      if (!result.ok) {
        if (result.failure.kind === "contract" && result.failure.error.code === "ENDPOINT_NOT_FOUND") {
          setCatalogUnavailable(true);
          controller.abort();
          clearTimers();
        } else {
          failed.add(tile);
          setFailedScope(tiles);
        }
        return;
      }
      setResults((current) => ({
        ...Object.fromEntries(Object.entries(current).filter(([key]) => tiles.includes(key))),
        [tile]: result.body,
      }));
      if (result.body.status === "PENDING") {
        pending.add(tile);
        if (document.visibilityState === "visible") {
          const timer = window.setTimeout(() => {
            timers.delete(timer);
            void load(tile);
          }, 15_000);
          timers.add(timer);
        }
      }
    }

    function resumePending() {
      clearTimers();
      pending.forEach((tile) => void load(tile));
    }

    retryFailed.current = () => {
      failed.forEach((tile) => {
        pending.add(tile);
        void load(tile);
      });
      failed.clear();
      setFailedScope(null);
    };
    document.addEventListener("visibilitychange", resumePending);
    resumePending();

    return () => {
      controller.abort();
      clearTimers();
      document.removeEventListener("visibilitychange", resumePending);
      retryFailed.current = null;
    };
  }, [tiles, catalogUnavailable]);

  const stops = useMemo(() => {
    if (catalogUnavailable) return fallback;
    const stopsById = new Map<string, HomeStop>();
    for (const tile of tiles) {
      for (const { stationId, name, point } of results[tile]?.stations ?? []) {
        stopsById.set(stationId, { id: stationId, name, point: [point.x, point.y], routes: [], source: "station-api" });
      }
    }
    return [...stopsById.values()];
  }, [catalogUnavailable, fallback, results, tiles]);

  return {
    stops,
    hasError: !catalogUnavailable && failedScope === tiles,
    retry: () => retryFailed.current?.(),
  };
}
