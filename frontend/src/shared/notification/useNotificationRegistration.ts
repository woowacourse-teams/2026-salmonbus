import { useEffect, useRef } from "react";
import type { ApiResult } from "../api/client";
import { registerPushDevice, unregisterPushDevice } from "../api/notifications.api";
import { memberIdOf, useAuth } from "../hooks/useAuth";
import { getNotificationRegistration } from "./nativeNotification";
import type { ApnsEnvironment, NotificationRegistration } from "./notification.types";

const UNREGISTER_TIMEOUT_MS = 3_000;

let registeredToken: string | null = null;

const registrationsInFlight = new Set<Promise<unknown>>();

function trackRegistration<T>(registration: Promise<T>): Promise<T> {
  registrationsInFlight.add(registration);
  void registration.then(
    () => registrationsInFlight.delete(registration),
    () => registrationsInFlight.delete(registration),
  );
  return registration;
}

export function registerThisDevice(
  token: string,
  environment: ApnsEnvironment,
  register: typeof registerPushDevice = registerPushDevice,
): Promise<ApiResult<void>> {
  return trackRegistration(
    (async () => {
      const result = await register(token, environment);
      if (result.ok) registeredToken = token;
      return result;
    })(),
  );
}

export function useNotificationRegistration() {
  const memberId = memberIdOf(useAuth().state);
  const registeredMemberId = useRef<string | null>(null);
  useEffect(() => {
    if (memberId === undefined || registeredMemberId.current === memberId) return;
    registeredMemberId.current = memberId;
    if (memberId !== null) void registerDeviceIfGranted();
  }, [memberId]);
}

export function registerDeviceIfGranted(
  readRegistration: () => Promise<NotificationRegistration | null> = getNotificationRegistration,
  register: typeof registerPushDevice = registerPushDevice,
): Promise<void> {
  return trackRegistration(
    (async () => {
      const registration = await readRegistration();
      if (registration?.status !== "granted" || registration.token === null) return;
      await registerThisDevice(registration.token, registration.environment, register);
    })(),
  );
}

interface UnregisterOptions {
  unregister?: typeof unregisterPushDevice;
  readRegistration?: () => Promise<NotificationRegistration | null>;
  timeoutMs?: number;
}

export async function unregisterThisDevice({
  unregister = unregisterPushDevice,
  readRegistration = getNotificationRegistration,
  timeoutMs = UNREGISTER_TIMEOUT_MS,
}: UnregisterOptions = {}): Promise<void> {
  await settledWithin(Promise.allSettled(registrationsInFlight), timeoutMs);
  const remembered = registeredToken;
  registeredToken = null;
  await settledWithin(
    (async () => {
      const token = remembered ?? grantedTokenOf(await readRegistration());
      if (token !== null) await unregister(token);
    })(),
    timeoutMs,
  );
}

function settledWithin(work: Promise<unknown>, timeoutMs: number): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, timeoutMs);
    void work
      .catch(() => {})
      .finally(() => {
        clearTimeout(timer);
        resolve();
      });
  });
}

function grantedTokenOf(registration: NotificationRegistration | null): string | null {
  return registration?.status === "granted" ? registration.token : null;
}
