import { useEffect, useEffectEvent, useState } from "react";
import type { ApiResult } from "./client";
import { createLatestRequestGate } from "./latestRequestGate";
import { nextPollFrom } from "./pollSchedule";
import { nextResourceFrom, PENDING_RESOURCE, type PolledResource } from "./polledResource";

interface PolledState<T> {
  key: string;
  resource: PolledResource<T>;
}

export function usePolledRequest<T>(
  request: (key: string, signal: AbortSignal) => Promise<ApiResult<T>>,
  key: string,
): PolledResource<T> {
  const [state, setState] = useState<PolledState<T> | null>(null);
  const requestResource = useEffectEvent(request);

  useEffect(() => {
    const gate = createLatestRequestGate();
    let timer: number | undefined;
    let disposed = false;

    const clearTimer = () => {
      if (timer !== undefined) {
        window.clearTimeout(timer);
        timer = undefined;
      }
    };

    const load = () => {
      clearTimer();
      const ticket = gate.issue();
      void requestResource(key, ticket.signal).then((result) => {
        if (disposed || !ticket.isLatest()) {
          return;
        }

        const receivedAt = Date.now();
        setState((previous) => ({
          key,
          resource: nextResourceFrom(previous?.key === key ? previous.resource : PENDING_RESOURCE, result, receivedAt),
        }));

        const decision = nextPollFrom(result);
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
      }
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    if (document.visibilityState === "visible") {
      load();
    }

    return () => {
      disposed = true;
      clearTimer();
      document.removeEventListener("visibilitychange", onVisibilityChange);
      gate.close();
    };
  }, [key]);

  return state?.key === key ? state.resource : PENDING_RESOURCE;
}
