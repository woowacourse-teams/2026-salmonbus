import { useEffect, useRef } from "react";
import type { ApiResult } from "../api/client";
import { registerPushDevice, unregisterPushDevice } from "../api/notifications.api";
import { memberIdOf, useAuth } from "../hooks/useAuth";
import { getNotificationRegistration } from "./nativeNotification";
import type { ApnsEnvironment, NotificationRegistration } from "./notification.types";

const UNREGISTER_TIMEOUT_MS = 3_000;

let registeredToken: string | null = null;
let registrationEpoch = 0;
let acceptedEpoch = 0;
let registrationsOpen = true;

const registrationsInFlight = new Set<Promise<unknown>>();
const registrationClosed: ApiResult<void> = { ok: false, failure: { kind: "aborted" } };

function trackRegistration<T>(registration: Promise<T>): Promise<T> {
  registrationsInFlight.add(registration);
  void registration.then(
    () => registrationsInFlight.delete(registration),
    () => registrationsInFlight.delete(registration),
  );
  return registration;
}

export function allowDeviceRegistration(): void {
  registrationsOpen = true;
}

export function registerThisDevice(
  token: string,
  environment: ApnsEnvironment,
  register: typeof registerPushDevice = registerPushDevice,
): Promise<ApiResult<void>> {
  const epoch = registrationEpoch;
  if (!registrationsOpen || epoch !== acceptedEpoch) return Promise.resolve(registrationClosed);
  return trackRegistration(
    (async () => {
      const result = await register(token, environment);
      if (result.ok && epoch === acceptedEpoch) registeredToken = token;
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
    if (memberId === null) return;
    allowDeviceRegistration();
    void registerDeviceIfGranted();
  }, [memberId]);
}

export function registerDeviceIfGranted(
  readRegistration: () => Promise<NotificationRegistration | null> = getNotificationRegistration,
  register: typeof registerPushDevice = registerPushDevice,
): Promise<void> {
  const epoch = registrationEpoch;
  if (!registrationsOpen || epoch !== acceptedEpoch) return Promise.resolve();
  return trackRegistration(
    (async () => {
      const registration = await readRegistration();
      if (!registrationsOpen || epoch !== acceptedEpoch) return;
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
  registrationsOpen = false;
  registrationEpoch += 1;
  await settledWithin(Promise.allSettled(registrationsInFlight), timeoutMs);
  acceptedEpoch = registrationEpoch;
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
