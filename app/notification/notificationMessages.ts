import type { ApnsEnvironment, NotificationCommandType } from '../../frontend/src/shared/notification/notification.types';

export const NOTIFICATION_CAPABILITIES = [
  'notification.requestPermission',
  'notification.getRegistration',
  'notification.takeOpened',
] as const satisfies readonly NotificationCommandType[];
export const REGISTRATION_EVENT = 'notification.registration';
export const OPENED_EVENT = 'notification.opened';

export type ReleaseKind = 'simulator' | 'appStore' | 'other';

const REQUEST_ID = /^[A-Za-z0-9-]{1,64}$/;

export function notificationRequestIdFrom(payload: Record<string, unknown>): string | null {
  const { requestId } = payload;
  return typeof requestId === 'string' && REQUEST_ID.test(requestId) ? requestId : null;
}

export function openedTargetFrom(value: unknown): Record<string, unknown> | null {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return null;
  return value as Record<string, unknown>;
}

export function apnsEnvironmentFrom(apsEnvironment: string | null, release: ReleaseKind): ApnsEnvironment | null {
  if (apsEnvironment === 'development') return 'SANDBOX';
  if (apsEnvironment === 'production') return 'PRODUCTION';
  if (apsEnvironment !== null) return null;
  if (release === 'appStore') return 'PRODUCTION';
  if (release === 'simulator') return 'SANDBOX';
  return null;
}
