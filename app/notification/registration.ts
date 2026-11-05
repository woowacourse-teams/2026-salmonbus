import * as Application from 'expo-application';
import * as Notifications from 'expo-notifications';
import type {
  ApnsEnvironment,
  NotificationDevice,
  NotificationRegistration,
} from '../../frontend/src/shared/notification/notification.types';
import { settleWithin } from '../auth/settleWithin';
import { apnsEnvironmentFrom, type ReleaseKind } from './notificationMessages';

const TOKEN_TIMEOUT_MS = 10 * 1000;
const NO_DEVICE: NotificationDevice = { token: null, environment: null };

export async function registrationFor(requestId: string, { ask }: { ask: boolean }): Promise<NotificationRegistration> {
  const status = await permissionStatus(ask);
  if (status !== Notifications.PermissionStatus.GRANTED) return { requestId, status, ...NO_DEVICE };
  return { requestId, status: 'granted', ...(await thisDevice()) };
}

async function permissionStatus(ask: boolean): Promise<Notifications.PermissionStatus> {
  try {
    const { status } = ask
      ? await Notifications.requestPermissionsAsync({ ios: { allowAlert: true, allowSound: true, allowBadge: false } })
      : await Notifications.getPermissionsAsync();
    return status;
  } catch (error) {
    if (__DEV__) console.warn('알림 권한을 읽지 못했습니다', error);
    return Notifications.PermissionStatus.UNDETERMINED;
  }
}

async function thisDevice(): Promise<NotificationDevice> {
  try {
    const environment = await apnsEnvironment();
    if (environment === null) {
      if (__DEV__) console.warn('APNs 환경을 정할 수 없어 기기 토큰을 넘기지 않습니다');
      return NO_DEVICE;
    }
    const { data } = await settleWithin(Notifications.getDevicePushTokenAsync(), TOKEN_TIMEOUT_MS);
    return typeof data === 'string' && data !== '' ? { token: data, environment } : NO_DEVICE;
  } catch (error) {
    if (__DEV__) console.warn('APNs 환경이나 기기 토큰을 읽지 못했습니다', error);
    return NO_DEVICE;
  }
}

async function apnsEnvironment(): Promise<ApnsEnvironment | null> {
  const [apsEnvironment, releaseType] = await Promise.all([
    Application.getIosPushNotificationServiceEnvironmentAsync(),
    Application.getIosApplicationReleaseTypeAsync(),
  ]);
  return apnsEnvironmentFrom(apsEnvironment, releaseKindOf(releaseType));
}

function releaseKindOf(releaseType: Application.ApplicationReleaseType): ReleaseKind {
  if (releaseType === Application.ApplicationReleaseType.SIMULATOR) return 'simulator';
  if (releaseType === Application.ApplicationReleaseType.APP_STORE) return 'appStore';
  return 'other';
}
