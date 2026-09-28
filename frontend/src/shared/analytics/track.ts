import { track as sendEvent } from "@amplitude/unified";
import { isAnalyticsEnabled } from "./analytics";
import type { EventMap } from "./events";

export function track<E extends keyof EventMap>(event: E, properties: EventMap[E]) {
  if (!isAnalyticsEnabled()) return;
  sendEvent(event, properties);
}
