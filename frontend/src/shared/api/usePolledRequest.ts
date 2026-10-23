import { useEffect, useEffectEvent, useState } from "react";
import type { ApiResult } from "./client";
import { createLatestRequestGate } from "./latestRequestGate";
import { nextPollFrom } from "./pollSchedule";
import { nextResourceFrom, IDLE_RESOURCE, PENDING_RESOURCE, type PolledResource } from "./polledResource";

interface PolledState<T> {
  key: string;
  resource: PolledResource<T>;
}

export function usePolledRequest<T>(
  request: (key: string, signal: AbortSignal) => Promise<ApiResult<T>>,
  key: string | null,
  revision: string | number = 0,
): PolledResource<T> {
  const [state, setState] = useState<PolledState<T> | null>(null);
  const requestResource = useEffectEvent(request);

  useEffect(() => {
    if (key === null) return;

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

    const onOnline = () => {
      if (document.visibilityState === "visible") {
        load();
      }
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    window.addEventListener("online", onOnline);
    if (document.visibilityState === "visible") {
      load();
    }

    return () => {
      disposed = true;
      clearTimer();
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.removeEventListener("online", onOnline);
      gate.close();
    };
  }, [key, revision]);

  if (key === null) return IDLE_RESOURCE;
  return state?.key === key ? state.resource : PENDING_RESOURCE;
}
