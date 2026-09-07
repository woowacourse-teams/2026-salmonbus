import { useEffect, useState } from "react";
import type { ApiResult } from "./client";
import { createLatestRequestGate } from "./latestRequestGate";
import { nextPollFrom } from "./pollSchedule";

export interface PolledResource<T> {
  result: ApiResult<T> | null;
  // 마지막으로 성공한 본문. 갱신이 실패해도 남는다.
  body: T | null;
}

interface PolledState<T> extends PolledResource<T> {
  key: string;
}

const EMPTY = { result: null, body: null };

// 다음 호출 시각은 서버가 Cache-Control에 담아 보낸다. 화면이 주기를 계산하지 않는다.
export function usePolledRequest<T>(
  request: (key: string, signal: AbortSignal) => Promise<ApiResult<T>>,
  key: string,
): PolledResource<T> {
  const [state, setState] = useState<PolledState<T> | null>(null);

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
      void request(key, ticket.signal).then((result) => {
        if (disposed || !ticket.isLatest()) {
          return;
        }

        setState((previous) => ({
          key,
          result,
          body: result.ok ? result.body : previous?.key === key ? previous.body : null,
        }));

        const decision = nextPollFrom(result);
        if (decision.kind === "again" && document.visibilityState === "visible") {
          timer = window.setTimeout(load, decision.delayMs);
        }
      });
    };

    // 배경으로 열려만 있는 화면은 부르지 않는다. 돌아오면 바로 한 번 부른다.
    const onVisibilityChange = () => {
      if (document.visibilityState === "visible") {
        load();
      } else {
        clearTimer();
      }
    };

    document.addEventListener("visibilitychange", onVisibilityChange);
    load();

    return () => {
      disposed = true;
      clearTimer();
      document.removeEventListener("visibilitychange", onVisibilityChange);
      gate.close();
    };
  }, [request, key]);

  return state?.key === key ? state : EMPTY;
}
