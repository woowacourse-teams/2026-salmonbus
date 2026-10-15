import { useEffect, useMemo, useState } from "react";
import { createLatestRequestGate } from "@/shared/api/latestRequestGate";
import { PENDING_RESOURCE, type PolledResource } from "@/shared/api/polledResource";
import { fetchStationArrivals } from "@/shared/api/stationArrivals.api";
import {
  nextStationArrivalPollFrom,
  nextStationArrivalsResource,
  stationArrivalSelectionKey,
  withStationArrivalClock,
} from "@/shared/api/stationArrivalsPolling";
import type { StationArrivalSelection, StationArrivalsResponse } from "@/shared/api/stationArrivals.types";

export type { StationArrivalSelection } from "@/shared/api/stationArrivals.types";

interface SelectionIdentity {
  key: string;
  selection: StationArrivalSelection;
}

interface ArrivalState {
  identity: SelectionIdentity;
  resource: PolledResource<StationArrivalsResponse>;
}

export function useStationArrivals(selection: StationArrivalSelection | null): PolledResource<StationArrivalsResponse> {
  const stationId = selection?.stationId ?? null;
  const routeId = selection?.routeId ?? null;
  const referenceVersionId = selection?.referenceVersionId ?? null;
  const stopSequence = selection?.stopSequence ?? null;
  const direction = selection?.direction ?? null;
  const identity = useMemo(() => {
    if (
      stationId === null ||
      routeId === null ||
      referenceVersionId === null ||
      stopSequence === null ||
      direction === null
    ) {
      return null;
    }
    const current = { stationId, routeId, referenceVersionId, stopSequence, direction };
    return { key: stationArrivalSelectionKey(current), selection: current };
  }, [stationId, routeId, referenceVersionId, stopSequence, direction]);
  const [state, setState] = useState<ArrivalState | null>(null);

  useEffect(() => {
    if (identity === null) return;

    const gate = createLatestRequestGate();
    let timer: number | undefined;
    let disposed = false;
    let stopped = false;
    let resource: PolledResource<StationArrivalsResponse> = PENDING_RESOURCE;

    const clearTimer = () => {
      if (timer === undefined) return;
      window.clearTimeout(timer);
      timer = undefined;
    };

    const load = () => {
      if (disposed || stopped) return;
      clearTimer();
      const ticket = gate.issue();
      void fetchStationArrivals(identity.selection, { signal: ticket.signal }).then((response) => {
        if (disposed || !ticket.isLatest()) return;

        const result = withStationArrivalClock(response);
        resource = nextStationArrivalsResource(resource, result, Date.now());
        setState({ identity, resource });

        const decision = nextStationArrivalPollFrom(result);
        stopped = decision.kind === "stop";
        if (decision.kind === "again" && document.visibilityState === "visible") {
          timer = window.setTimeout(load, decision.delayMs);
        }
      });
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        load();
      } else {
        clearTimer();
        gate.close();
      }
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    if (document.visibilityState === "visible") load();

    return () => {
      disposed = true;
      clearTimer();
      gate.close();
      document.removeEventListener("visibilitychange", onVisibilityChange);
    };
  }, [identity]);

  return identity !== null && state?.identity === identity ? state.resource : PENDING_RESOURCE;
}
