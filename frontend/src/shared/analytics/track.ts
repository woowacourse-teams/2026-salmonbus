import { flush, setTransport, track as sendEvent } from "@amplitude/unified";
import { isAnalyticsEnabled, setAnalyticsMemberId } from "./analytics";
import type { EventMap } from "./events";

const LEAVING_WAIT_LIMIT_MS = 300;

export function track<E extends keyof EventMap>(event: E, properties: EventMap[E]) {
  if (!isAnalyticsEnabled()) return;
  sendEvent(event, properties);
}

// 회원 ID를 먼저 맞춘 뒤 보내서, 앱 조립 순서와 상관없이 이 이벤트에 회원 ID가 붙는다.
export function trackAsMember<E extends keyof EventMap>(memberId: string, event: E, properties: EventMap[E]) {
  setAnalyticsMemberId(memberId);
  track(event, properties);
}

// SDK는 이벤트를 다음 틱에 큐로 옮겨서, 곧바로 다른 사이트로 떠나면 이벤트가 큐에 닿기 전에 사라진다.
// 큐를 바로 비우고, 응답을 기다리지 않는 beacon으로 보내서 화면 이동을 늦추지 않는다.
export async function trackBeforeLeaving<E extends keyof EventMap>(event: E, properties: EventMap[E]) {
  if (!isAnalyticsEnabled()) return;
  try {
    setTransport("beacon");
    const sent = sendEvent(event, properties).promise;
    flush();
    await Promise.race([sent, new Promise((resolve) => window.setTimeout(resolve, LEAVING_WAIT_LIMIT_MS))]);
  } catch {
    /* 이벤트를 보내지 못해도 화면 이동은 막지 않는다. */
  } finally {
    setTransport("fetch");
  }
}
