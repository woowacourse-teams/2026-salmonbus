import { useEffect, useRef } from "react";
import type { ApiResult } from "../api/client";
import { registerPushDevice, unregisterPushDevice } from "../api/notifications.api";
import { memberIdOf, useAuth } from "../hooks/useAuth";
import { getNotificationRegistration } from "./nativeNotification";
import type { ApnsEnvironment, NotificationRegistration } from "./notification.types";

/** 앱이 답을 잃거나 서버가 늦어도 로그아웃은 이 시간 안에 이어진다. */
const UNREGISTER_TIMEOUT_MS = 3_000;

/** 서버 등록에 성공한 이 기기의 token. 로그아웃 때 이 token을 지운다. */
let registeredToken: string | null = null;

// 로그아웃이 등록보다 먼저 서버에 닿으면 로그아웃한 기기가 등록된 채로 남는다. 로그아웃은 이 등록들이 끝나길 기다린다.
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

/**
 * 로그인한 회원마다 한 번, 권한 창 없이 앱에 지금 token을 물어 서버에 등록한다.
 * APNs가 token을 바꿨거나 이전 로그아웃이 이 기기를 지웠을 수 있어 앱을 열 때와 로그인할 때마다 다시 등록한다.
 */
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

/**
 * 로그아웃 직전에 부른다. 다음 회원에게 이 회원의 알림이 가지 않게 이 기기 token을 서버에서 지운다.
 * 진행 중인 등록을 기다리는 것과 지우는 것에 각각 시간 제한을 두어, 실패하거나 늦어도 로그아웃은 막지 않는다.
 */
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
      // 이 페이지에서 등록하지 않았어도 이전 페이지나 이전 실행에서 등록한 token일 수 있다.
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
