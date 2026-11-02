import type { ApnsEnvironment, NotificationCommandType } from '../../frontend/src/shared/notification/notification.types';

// 웹의 frontend/src/shared/notification/notification.types.ts와 같은 메시지를 주고받는다. 웹의 bridge.test.ts가 목록을 비교한다.
export const NOTIFICATION_CAPABILITIES = [
  'notification.requestPermission',
  'notification.getRegistration',
  'notification.takeOpened',
] as const satisfies readonly NotificationCommandType[];
export const REGISTRATION_EVENT = 'notification.registration';
export const OPENED_EVENT = 'notification.opened';

/** simulator와 appStore만 서명 프로파일 없이 APNs 환경을 정할 수 있다. */
export type ReleaseKind = 'simulator' | 'appStore' | 'other';

const REQUEST_ID = /^[A-Za-z0-9-]{1,64}$/;

export function notificationRequestIdFrom(payload: Record<string, unknown>): string | null {
  const { requestId } = payload;
  return typeof requestId === 'string' && REQUEST_ID.test(requestId) ? requestId : null;
}

/**
 * 알림 payload의 target을 고치지 않고 웹에 넘긴다. 대상 형식은 웹(nativeNotification.ts) 한 곳에서 읽는다.
 * 앱이 형식을 판단하면 서버가 target에 값을 더할 때 이미 깔린 앱이 그 값을 버린다.
 */
export function openedTargetFrom(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

/**
 * 서명 프로파일의 aps-environment를 먼저 본다. App Store와 TestFlight 빌드는 Apple이 프로파일을 빼서
 * 값이 없지만 늘 production이다. 시뮬레이터는 sandbox다. 그 밖에 값이 없으면 정할 수 없다.
 */
export function apnsEnvironmentFrom(apsEnvironment: string | null, release: ReleaseKind): ApnsEnvironment | null {
  if (apsEnvironment === 'development') return 'SANDBOX';
  if (apsEnvironment === 'production') return 'PRODUCTION';
  if (apsEnvironment !== null) return null;
  if (release === 'appStore') return 'PRODUCTION';
  if (release === 'simulator') return 'SANDBOX';
  return null;
}
