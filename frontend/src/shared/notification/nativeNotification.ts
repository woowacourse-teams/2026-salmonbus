import { isSupportedInApp, sendToApp, subscribeToApp, type BridgeWindow } from "../bridge/bridge";
import type { BridgeMessage } from "../bridge/bridge.types";
import type {
  NotificationCommandType,
  NotificationDevice,
  NotificationPermissionStatus,
  NotificationRegistration,
  NotificationTarget,
  NotificationEvents,
  OpenedNotification,
} from "./notification.types";

/** 권한 창은 사용자가 고를 때까지 떠 있다. 이 시간이 지나도 답이 없으면 앱이 답을 잃은 것으로 본다. */
const PERMISSION_TIMEOUT_MS = 5 * 60 * 1000;
/** 앱은 device token을 10초까지 기다린다. */
const REGISTRATION_TIMEOUT_MS = 15 * 1000;
const TAKE_OPENED_TIMEOUT_MS = 5 * 1000;

const PERMISSION_STATUSES: readonly NotificationPermissionStatus[] = ["granted", "denied", "undetermined"];

/** 앱이 알림 명령을 받을 수 있는지. 브라우저와 옛 앱은 false라서 알림 기능을 숨긴다. */
export function isNativeNotificationSupported(bridgeWindow?: BridgeWindow): boolean {
  return isSupportedInApp("notification.getRegistration", bridgeWindow);
}

/** 권한을 묻는다. 이미 허용이나 거절을 골랐으면 iOS가 창을 띄우지 않고 지금 상태를 돌려준다. 답이 없으면 null이다. */
export function requestNotificationPermission(
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<NotificationRegistration | null> {
  return requestRegistration("notification.requestPermission", PERMISSION_TIMEOUT_MS, bridgeWindow, target);
}

/** 권한 창 없이 지금 권한과 token을 읽는다. 답이 없으면 null이다. */
export function getNotificationRegistration(
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<NotificationRegistration | null> {
  return requestRegistration("notification.getRegistration", REGISTRATION_TIMEOUT_MS, bridgeWindow, target);
}

/** 앱이 쌓아 둔 알림 탭 대상을 꺼낸다. 쌓인 것이 없거나 답이 없으면 null이다. */
export async function takeOpenedNotification(
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<NotificationTarget | null> {
  const opened = await requestFromApp(
    "notification.takeOpened",
    "notification.opened",
    openedNotificationFrom,
    TAKE_OPENED_TIMEOUT_MS,
    bridgeWindow,
    target,
  );
  return opened?.target ?? null;
}

function requestRegistration(
  type: "notification.requestPermission" | "notification.getRegistration",
  timeoutMs: number,
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<NotificationRegistration | null> {
  return requestFromApp(
    type,
    "notification.registration",
    notificationRegistrationFrom,
    timeoutMs,
    bridgeWindow,
    target,
  );
}

/** 같은 requestId로 온 답만 받는다. 앱 밖이거나 앱이 명령을 모르면 보내지 않고 바로 null이다. */
function requestFromApp<T extends { requestId: string | null }>(
  type: NotificationCommandType,
  replyType: keyof NotificationEvents,
  parse: (payload: object) => T | null,
  timeoutMs: number,
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<T | null> {
  if (!isSupportedInApp(type, bridgeWindow)) return Promise.resolve(null);
  const requestId = crypto.randomUUID();
  return new Promise((resolve) => {
    const finish = (reply: T | null) => {
      clearTimeout(timer);
      unsubscribe();
      resolve(reply);
    };
    const timer = setTimeout(() => finish(null), timeoutMs);
    const unsubscribe = subscribeToApp((message: BridgeMessage) => {
      if (message.type !== replyType) return;
      const reply = parse(message.payload);
      if (reply !== null && reply.requestId === requestId) finish(reply);
    }, target);
    sendToApp(type, { requestId }, bridgeWindow);
  });
}

export function notificationRegistrationFrom(payload: object): NotificationRegistration | null {
  const value = payload as Record<string, unknown>;
  const { requestId, status } = value;
  if (typeof requestId !== "string") return null;
  const knownStatus = PERMISSION_STATUSES.find((known) => known === status);
  if (knownStatus === undefined) return null;
  return { requestId, status: knownStatus, ...notificationDeviceFrom(value) };
}

/** token과 environment가 함께 맞을 때만 기기로 본다. 하나라도 어긋나면 등록하지 않게 둘 다 null로 둔다. */
function notificationDeviceFrom({ token, environment }: Record<string, unknown>): NotificationDevice {
  if (typeof token !== "string" || token === "") return { token: null, environment: null };
  if (environment !== "SANDBOX" && environment !== "PRODUCTION") return { token: null, environment: null };
  return { token, environment };
}

export function openedNotificationFrom(payload: object): OpenedNotification | null {
  const { requestId, target } = payload as Record<string, unknown>;
  if (requestId !== null && typeof requestId !== "string") return null;
  return { requestId, target: notificationTargetFrom(target) };
}

export function notificationTargetFrom(value: unknown): NotificationTarget | null {
  if (value === null || typeof value !== "object") return null;
  const { routeId, stopId, direction } = value as Record<string, unknown>;
  if (typeof routeId !== "string" || routeId === "" || typeof stopId !== "string" || stopId === "") return null;
  if (direction !== "UP" && direction !== "DOWN") return null;
  return { routeId, stopId, direction };
}
