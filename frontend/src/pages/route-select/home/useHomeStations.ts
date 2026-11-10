import { useEffect, useMemo, useState } from "react";
import { fetchStationTile, type StationTileResult } from "@/shared/api/stationMap.api";
import type { HomeStop } from "./homeMapData";

export function useHomeStations(tileKey: string, fallback: HomeStop[]) {
  const tiles = useMemo(() => (tileKey ? tileKey.split(",") : []), [tileKey]);
  const [catalogUnavailable, setCatalogUnavailable] = useState(false);
  const [results, setResults] = useState<Record<string, StationTileResult>>({});

  useEffect(() => {
    if (tiles.length === 0 || catalogUnavailable) return;
    const controller = new AbortController();
    const timers = new Set<number>();
    async function load(tile: string) {
      const result = await fetchStationTile(tile, controller.signal);
      if (controller.signal.aborted) return;
      if (!result.ok) {
        if (result.failure.kind === "contract" && result.failure.error.code === "ENDPOINT_NOT_FOUND") {
          setCatalogUnavailable(true);
          controller.abort();
        }
        return;
      }
      setResults((current) => ({
        ...Object.fromEntries(Object.entries(current).filter(([key]) => tiles.includes(key))),
        [tile]: result.body,
      }));
      if (result.body.status === "PENDING" && document.visibilityState === "visible") {
        const timer = window.setTimeout(() => {
          timers.delete(timer);
          if (document.visibilityState === "visible") void load(tile);
        }, 15_000);
        timers.add(timer);
      }
    }
    tiles.forEach((tile) => void load(tile));
    return () => {
      controller.abort();
      timers.forEach((timer) => window.clearTimeout(timer));
    };
  }, [tiles, catalogUnavailable]);

  return useMemo(() => {
    if (catalogUnavailable) return fallback;
    const stops = new Map<string, HomeStop>();
    for (const tile of tiles) {
      for (const { stationId, name, point } of results[tile]?.stations ?? []) {
        stops.set(stationId, { id: stationId, name, point: [point.x, point.y], routes: [], source: "station-api" });
      }
    }
    return [...stops.values()];
  }, [catalogUnavailable, fallback, results, tiles]);
}
