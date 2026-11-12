import { createBridgeId, isSupportedInApp, sendToApp, subscribeToApp, type BridgeWindow } from "../bridge/bridge";
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

const PERMISSION_TIMEOUT_MS = 5 * 60 * 1000;
const REGISTRATION_TIMEOUT_MS = 15 * 1000;
const TAKE_OPENED_TIMEOUT_MS = 5 * 1000;

const PERMISSION_STATUSES: readonly NotificationPermissionStatus[] = ["granted", "denied", "undetermined"];

export function isNativeNotificationSupported(bridgeWindow?: BridgeWindow): boolean {
  return isSupportedInApp("notification.getRegistration", bridgeWindow);
}

export function requestNotificationPermission(
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<NotificationRegistration | null> {
  return requestRegistration("notification.requestPermission", PERMISSION_TIMEOUT_MS, bridgeWindow, target);
}

export function getNotificationRegistration(
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<NotificationRegistration | null> {
  return requestRegistration("notification.getRegistration", REGISTRATION_TIMEOUT_MS, bridgeWindow, target);
}

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

function requestFromApp<T extends { requestId: string | null }>(
  type: NotificationCommandType,
  replyType: keyof NotificationEvents,
  parse: (payload: object) => T | null,
  timeoutMs: number,
  bridgeWindow?: BridgeWindow,
  target?: EventTarget,
): Promise<T | null> {
  if (!isSupportedInApp(type, bridgeWindow)) return Promise.resolve(null);
  const requestId = createBridgeId();
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
