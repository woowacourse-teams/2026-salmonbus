import { useEffect, useEffectEvent, useState } from "react";
import type { ApiResult } from "./client";
import { createLatestRequestGate } from "./latestRequestGate";
import { nextPollFrom } from "./pollSchedule";
import { nextResourceFrom, IDLE_RESOURCE, PENDING_RESOURCE, type PolledResource } from "./polledResource";

interface PolledState<T> {
  key: string;
  revision: string | number;
  resource: PolledResource<T>;
  requestState: PolledRequestState;
}

export type PolledRequestState = "idle" | "loading" | "scheduled" | "stopped";

export interface PolledRequest<T> {
  resource: PolledResource<T>;
  requestState: PolledRequestState;
}

export function usePolledRequest<T>(
  request: (key: string, signal: AbortSignal) => Promise<ApiResult<T>>,
  key: string | null,
  revision: string | number = 0,
): PolledRequest<T> {
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
      setState((previous) => ({
        key,
        revision,
        resource: previous?.key === key ? previous.resource : PENDING_RESOURCE,
        requestState: "loading",
      }));
      void requestResource(key, ticket.signal).then((result) => {
        if (disposed || !ticket.isLatest()) {
          return;
        }

        const receivedAt = Date.now();
        const decision = nextPollFrom(result);
        const scheduled = decision.kind === "again" && document.visibilityState === "visible";
        setState((previous) => ({
          key,
          revision,
          resource: nextResourceFrom(previous?.key === key ? previous.resource : PENDING_RESOURCE, result, receivedAt),
          requestState: scheduled ? "scheduled" : "stopped",
        }));

        if (scheduled && decision.kind === "again") {
          timer = window.setTimeout(load, decision.delayMs);
        }
      });
    };

    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        load();
      } else {
        clearTimer();
        setState((previous) =>
          previous?.key === key && previous.requestState === "scheduled"
            ? { key, revision, resource: previous.resource, requestState: "stopped" }
            : previous,
        );
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

  if (key === null) return { resource: IDLE_RESOURCE, requestState: "idle" };
  return {
    resource: state?.key === key ? state.resource : PENDING_RESOURCE,
    requestState:
      state?.key === key && state.revision === revision
        ? state.requestState
        : document.visibilityState === "visible"
          ? "loading"
          : "stopped",
  };
}
