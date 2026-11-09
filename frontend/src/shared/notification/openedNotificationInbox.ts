import { subscribeToApp } from "../bridge/bridge";
import { openedNotificationFrom } from "./nativeNotification";
import type { NotificationTarget } from "./notification.types";

type OpenedNotificationListener = (target: NotificationTarget) => void;

export function createOpenedNotificationInbox() {
  const listeners = new Set<OpenedNotificationListener>();
  let held: NotificationTarget | null = null;

  function receive(target: NotificationTarget) {
    if (listeners.size === 0) {
      held = target;
      return;
    }
    listeners.forEach((listener) => listener(target));
  }

  function subscribe(listener: OpenedNotificationListener): () => void {
    listeners.add(listener);
    if (held !== null) {
      const target = held;
      held = null;
      listener(target);
    }
    return () => listeners.delete(listener);
  }

  return { receive, subscribe };
}

const inbox = createOpenedNotificationInbox();

export function initOpenedNotifications(target?: EventTarget): () => void {
  return subscribeToApp((message) => {
    if (message.type !== "notification.opened") return;
    const opened = openedNotificationFrom(message.payload);
    if (opened === null || opened.requestId !== null || opened.target === null) return;
    inbox.receive(opened.target);
  }, target);
}

export const subscribeToOpenedNotifications = inbox.subscribe;
