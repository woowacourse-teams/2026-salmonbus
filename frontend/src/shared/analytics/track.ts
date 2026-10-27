import { flush, setTransport, track as sendEvent } from "@amplitude/unified";
import { isAnalyticsEnabled } from "./analytics";
import type { EventMap } from "./events";

const LEAVING_WAIT_LIMIT_MS = 300;

export function track<E extends keyof EventMap>(event: E, properties: EventMap[E]) {
  if (!isAnalyticsEnabled()) return;
  sendEvent(event, properties);
}

// SDK는 이벤트를 모았다가 보내서, 바로 다른 사이트로 떠나면 큐에 들어가기도 전에 페이지가 사라진다.
// beacon은 페이지가 사라져도 브라우저가 보내 주므로 큐를 바로 비우고, 전송이 늦어도 이동은 막지 않는다.
export async function trackBeforeLeaving<E extends keyof EventMap>(event: E, properties: EventMap[E]) {
  if (!isAnalyticsEnabled()) return;
  setTransport("beacon");
  const sent = sendEvent(event, properties).promise;
  flush();
  await Promise.race([sent, new Promise((resolve) => window.setTimeout(resolve, LEAVING_WAIT_LIMIT_MS))]);
  setTransport("fetch");
}
