import { subscribeToApp } from "../bridge/bridge";
import { openedNotificationFrom } from "./nativeNotification";
import type { NotificationTarget } from "./notification.types";

type OpenedNotificationListener = (target: NotificationTarget) => void;

/**
 * 앱이 먼저 보낸 알림 대상(requestId가 null인 notification.opened)을 듣는 쪽에 넘긴다. 앱은 문서가 다 뜨면 보내고 잊는데,
 * 화면은 로그인 확인 뒤에야 들을 수 있다. 그래서 듣는 쪽이 없을 때 온 마지막 대상 하나를 첫 구독자에게 넘긴다.
 */
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

/** 웹 앱 시작 지점에서 한 번 부른다. 화면보다 먼저 notification.opened를 들어야 대상을 잃지 않는다. */
export function initOpenedNotifications(target?: EventTarget): () => void {
  return subscribeToApp((message) => {
    if (message.type !== "notification.opened") return;
    const opened = openedNotificationFrom(message.payload);
    // takeOpened의 답(requestId 있음)은 요청한 쪽이 받는다.
    if (opened === null || opened.requestId !== null || opened.target === null) return;
    inbox.receive(opened.target);
  }, target);
}

export const subscribeToOpenedNotifications = inbox.subscribe;
