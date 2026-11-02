import type { ApnsEnvironment } from "@/shared/api/notifications.types";
import { appHostFrom } from "@/shared/bridge/bridge";

// SAL-172가 shared/notification에 bridge 타입과 요청 함수를 커밋하면 이 파일을 그쪽으로 바꾼다.
// 그 전까지 이 파일이 알림 줄과 앱 사이의 유일한 연결 지점이다.

export interface NotificationRegistration {
  status: "granted" | "denied" | "undetermined";
  token: string | null;
  environment: ApnsEnvironment;
}

const PERMISSION_COMMAND = "notification.requestPermission";

/** 알림 권한을 물을 수 있는 앱 안에서만 알림 줄을 보인다. */
export function canRequestNotificationPermission(): boolean {
  return appHostFrom()?.capabilities.includes(PERMISSION_COMMAND) ?? false;
}

// 실제 앱 답을 받기 전까지는 권한을 묻지 않은 것으로 답해서 어떤 알림 API도 부르지 않는다.
export function requestNotificationRegistration(): Promise<NotificationRegistration> {
  return Promise.resolve({ status: "undetermined", token: null, environment: "PRODUCTION" });
}
