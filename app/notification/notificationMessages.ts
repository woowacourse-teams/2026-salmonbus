import type {
  ApnsEnvironment,
  NotificationCommandType,
  NotificationTarget,
} from '../../frontend/src/shared/notification/notification.types';

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
const TARGET_ID = /^[A-Za-z0-9]{1,32}$/;

export function notificationRequestIdFrom(payload: Record<string, unknown>): string | null {
  const { requestId } = payload;
  return typeof requestId === 'string' && REQUEST_ID.test(requestId) ? requestId : null;
}

/** 알림 payload의 target과 notification.opened의 target은 같은 모양이다. */
export function notificationTargetFrom(value: unknown): NotificationTarget | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
  const { routeId, stopId, direction } = value as Record<string, unknown>;
  if (typeof routeId !== 'string' || !TARGET_ID.test(routeId)) return null;
  if (typeof stopId !== 'string' || !TARGET_ID.test(stopId)) return null;
  if (direction !== 'UP' && direction !== 'DOWN') return null;
  return { routeId, stopId, direction };
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
