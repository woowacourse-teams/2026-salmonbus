import { useEffect, useEffectEvent, useState } from "react";
import type { Board } from "@/shared/api/routeForecast.types";
import { fetchRouteGeometry } from "@/shared/api/routeGeometry.api";
import {
  failedMapDataFrom,
  geometryFailureDecisionFor,
  loadedGeometryFor,
  loadedMapDataFrom,
  type LoadedMapData,
  type RouteMapResource,
} from "./routeMapResource";

const PREPARING_POLL_DELAY_MS = 60_000;

export type RouteMapGeometryStatus = RouteMapResource["status"];

export function useRouteMapData(
  board: Board,
  visible: boolean,
  onConflict: () => void,
): RouteMapResource & Pick<LoadedMapData, "retrying"> {
  const { id: routeId, referenceVersionId } = board.route;
  const identity = JSON.stringify([routeId, referenceVersionId]);
  const [loaded, setLoaded] = useState<LoadedMapData | null>(null);
  const reportConflict = useEffectEvent(onConflict);
  const hasReadyMap = useEffectEvent(
    (requestIdentity: string) => loaded?.identity === requestIdentity && loaded.resource.status === "ready",
  );

  useEffect(() => {
    if (!visible) return;
    const controller = new AbortController();
    let timer: number | undefined;
    let requesting = false;
    let conflicted = false;

    function clearTimer() {
      window.clearTimeout(timer);
      timer = undefined;
    }

    function schedule(delay = PREPARING_POLL_DELAY_MS) {
      if (document.visibilityState === "visible") timer = window.setTimeout(load, delay);
    }

    async function load() {
      clearTimer();
      if (requesting || conflicted || document.visibilityState !== "visible") return;
      requesting = true;
      const result = await fetchRouteGeometry(routeId, { signal: controller.signal, referenceVersionId });
      requesting = false;
      if (controller.signal.aborted) return;
      if (!result.ok) {
        const decision = geometryFailureDecisionFor(result, hasReadyMap(identity));
        switch (decision.kind) {
          case "conflict":
            conflicted = true;
            setLoaded({ identity, revision: null, resource: { status: "conflict", data: null }, retrying: false });
            reportConflict();
            break;
          case "keepReadyMap":
            break;
          case "retry":
            schedule(decision.delayMs);
            setLoaded((previous) => failedMapDataFrom(previous, identity, true));
            break;
          case "stop":
            setLoaded((previous) => failedMapDataFrom(previous, identity, false));
            break;
        }
        return;
      }

      const geometry = loadedGeometryFor(result.body, { id: routeId, referenceVersionId });
      setLoaded((previous) => loadedMapDataFrom(previous, identity, geometry));
      if (geometry.preparing) schedule();
    }

    function revalidateWhenVisible() {
      if (document.visibilityState === "visible") void load();
      else clearTimer();
    }

    document.addEventListener("visibilitychange", revalidateWhenVisible);
    window.addEventListener("online", revalidateWhenVisible);
    void load();
    return () => {
      clearTimer();
      controller.abort();
      document.removeEventListener("visibilitychange", revalidateWhenVisible);
      window.removeEventListener("online", revalidateWhenVisible);
    };
  }, [identity, routeId, referenceVersionId, visible]);

  return loaded?.identity === identity
    ? { ...loaded.resource, retrying: loaded.retrying }
    : { status: "loading", data: null, retrying: false };
}
