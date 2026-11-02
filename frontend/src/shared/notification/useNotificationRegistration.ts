import type { ApiResult } from "../api/client";
import { registerPushDevice, unregisterPushDevice } from "../api/notifications.api";
import { getNotificationRegistration } from "./nativeNotification";
import type { ApnsEnvironment, NotificationRegistration } from "./notification.types";

/** 앱이 답을 잃거나 서버가 늦어도 로그아웃은 이 시간 안에 이어진다. */
const UNREGISTER_TIMEOUT_MS = 3_000;

/** 서버 등록에 성공한 이 기기의 token. 로그아웃 때 이 token을 지운다. */
let registeredToken: string | null = null;

export async function registerThisDevice(
  token: string,
  environment: ApnsEnvironment,
  register: typeof registerPushDevice = registerPushDevice,
): Promise<ApiResult<void>> {
  const result = await register(token, environment);
  if (result.ok) registeredToken = token;
  return result;
}

interface UnregisterOptions {
  unregister?: typeof unregisterPushDevice;
  readRegistration?: () => Promise<NotificationRegistration | null>;
  timeoutMs?: number;
}

/** 로그아웃 직전에 부른다. 다음 회원에게 이 회원의 알림이 가지 않게 이 기기 token을 서버에서 지운다. 실패해도 로그아웃은 막지 않는다. */
export function unregisterThisDevice({
  unregister = unregisterPushDevice,
  readRegistration = getNotificationRegistration,
  timeoutMs = UNREGISTER_TIMEOUT_MS,
}: UnregisterOptions = {}): Promise<void> {
  const remembered = registeredToken;
  registeredToken = null;
  const removal = (async () => {
    // 이 페이지에서 등록하지 않았어도 이전 페이지나 이전 실행에서 등록한 token일 수 있다.
    const token = remembered ?? grantedTokenOf(await readRegistration());
    if (token !== null) await unregister(token);
  })();
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, timeoutMs);
    void removal
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
