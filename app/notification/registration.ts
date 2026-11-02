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

/**
 * ask가 true면 권한을 묻는다. 이미 고른 사용자에게는 iOS가 창을 띄우지 않는다.
 * 실패해도 reject하지 않고 결과로 돌려준다. 웹이 답을 못 받으면 알림 스위치가 기다린 채 남는다.
 */
export async function registrationFor(requestId: string, { ask }: { ask: boolean }): Promise<NotificationRegistration> {
  const status = await permissionStatus(ask);
  if (status !== Notifications.PermissionStatus.GRANTED) return { requestId, status, ...NO_DEVICE };
  return { requestId, status: 'granted', ...(await thisDevice()) };
}

// 메시지에 실패 상태가 없어서, 권한을 읽지 못하면 묻기 전 상태로 답한다. 웹은 알림 켜기를 다시 보여 주고 다시 물을 수 있다.
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

// 잘못된 환경으로 등록되면 발송이 조용히 실패한다. 그래서 환경을 정할 수 없으면 token을 넘기지 않는다.
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
