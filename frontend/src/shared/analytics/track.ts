import {
  isAnalyticsEnabled,
  createSendTicket,
  sendThroughAnalyticsSdk,
  setAnalyticsMemberId,
  whenAnalyticsSdkReady,
} from "./analytics";
import type { EventMap } from "./events";

const LEAVING_WAIT_LIMIT_MS = 300;

export function track<E extends keyof EventMap>(event: E, properties: EventMap[E]) {
  if (!isAnalyticsEnabled()) return;
  sendThroughAnalyticsSdk((amplitude, eventOptions) => amplitude.track(event, properties, eventOptions));
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
  const ticket = createSendTicket();
  const timeout = new Promise<null>((resolve) => window.setTimeout(resolve, LEAVING_WAIT_LIMIT_MS, null));
  const amplitude = await Promise.race([whenAnalyticsSdkReady(), timeout]);
  if (!amplitude || !ticket.isAllowed()) return;

  const restoreTransport = () => amplitude.setTransport("fetch");
  let sent: Promise<unknown>;
  try {
    amplitude.setTransport("beacon");
    sent = amplitude.track(event, properties, ticket.eventOptions).promise;
    amplitude.flush();
  } catch {
    /* 이벤트를 보내지 못해도 화면 이동은 막지 않는다. */
    restoreTransport();
    return;
  }
  // 한도가 지나 돌아가도 보내기가 끝나기 전에 되돌리면, 앞선 flush가 진행 중일 때 이 이벤트가 fetch로 나가 화면을 떠날 때 끊긴다.
  const sendFinished = sent.then(restoreTransport, restoreTransport);
  await Promise.race([sendFinished, timeout]);
}
