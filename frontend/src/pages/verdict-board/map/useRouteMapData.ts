import { useEffect, useEffectEvent, useState } from "react";
import type { Board } from "@/shared/api/routeForecast.types";
import { fetchRouteGeometry } from "@/shared/api/routeGeometry.api";
import { nextPollFrom } from "@/shared/api/pollSchedule";
import { routeGeometryMapDataFor } from "./routeGeometryMapData";
import type { RouteMapData } from "./routeMapModel";

const RETRY_DELAY_MS = 60_000;

type RouteMapResource =
  | { status: "loading" | "error" | "conflict"; data: null }
  | { status: "preparing" | "generating" | "withheld"; data: RouteMapData | null }
  | { status: "ready"; data: RouteMapData };

export type RouteMapGeometryStatus = RouteMapResource["status"];

interface LoadedMapData {
  identity: string;
  revision: string | null;
  resource: RouteMapResource;
}

export function useRouteMapData(board: Board, visible: boolean, onConflict: () => void): RouteMapResource {
  const { id: routeId, referenceVersionId } = board.route;
  const identity = JSON.stringify([routeId, referenceVersionId]);
  const [loaded, setLoaded] = useState<LoadedMapData | null>(null);
  const reportConflict = useEffectEvent(onConflict);

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

    function schedule(delay = RETRY_DELAY_MS) {
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
        const { failure } = result;
        if (
          failure.kind === "contract" &&
          failure.status === 409 &&
          failure.error.code === "REFERENCE_VERSION_CONFLICT"
        ) {
          conflicted = true;
          setLoaded({ identity, revision: null, resource: { status: "conflict", data: null } });
          reportConflict();
        } else {
          const decision = nextPollFrom(result);
          if (decision.kind === "again") {
            schedule(Math.max(RETRY_DELAY_MS, decision.delayMs));
          } else {
            setLoaded((previous) =>
              previous?.identity === identity &&
              (previous.resource.status === "ready" || previous.resource.status === "withheld")
                ? previous
                : { identity, revision: null, resource: { status: "error", data: null } },
            );
          }
        }
        return;
      }

      const geometry = result.body;
      const revision = typeof geometry.geometryRevision === "string" ? geometry.geometryRevision : null;
      const data = routeGeometryMapDataFor(geometry, { id: routeId, referenceVersionId });
      const reason = geometry.status === "UNAVAILABLE" ? geometry.reason : null;
      const preparing = reason === "NOT_COLLECTED" || reason === "GENERATION_PENDING";
      const resource: RouteMapResource = preparing
        ? { status: reason === "GENERATION_PENDING" ? "generating" : "preparing", data }
        : reason === "DISPLAY_WITHHELD"
          ? { status: "withheld", data }
          : data === null
            ? { status: "error", data: null }
            : { status: "ready", data };
      setLoaded((previous) =>
        revision !== null &&
        previous?.identity === identity &&
        previous.revision === revision &&
        previous.resource.status === resource.status
          ? previous
          : { identity, revision, resource },
      );
      if (preparing) schedule();
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

  return loaded?.identity === identity ? loaded.resource : { status: "loading", data: null };
}
