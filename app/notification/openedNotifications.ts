import * as Notifications from 'expo-notifications';
import { openedTargetFrom } from './notificationMessages';
import type { NotificationTap } from './tapInbox';

/** 앱이 앞에 떠 있을 때 온 알림도 배너로 보인다. 배지는 쓰지 않는다. */
export function showNotificationsInForeground() {
  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowBanner: true,
      shouldShowList: true,
      shouldPlaySound: true,
      shouldSetBadge: false,
    }),
  });
}

/**
 * 꺼진 앱을 연 탭은 리스너가 붙기 전에 와서 마지막 응답으로만 읽힌다. 그래서 둘 다 본다.
 * 같은 탭이 두 길로 올 수 있어 받는 쪽(tapInbox)이 key로 거른다.
 */
export function watchNotificationTaps(onTap: (tap: NotificationTap) => void): () => void {
  const receive = (response: Notifications.NotificationResponse) => {
    Notifications.clearLastNotificationResponse();
    const tap = tapFrom(response);
    if (tap !== null) onTap(tap);
  };
  const subscription = Notifications.addNotificationResponseReceivedListener(receive);
  const launchResponse = Notifications.getLastNotificationResponse();
  if (launchResponse !== null) receive(launchResponse);
  return () => subscription.remove();
}

// expo-notifications는 원격 알림의 data에 payload의 body 키만 넣는다. target은 알림 원본(trigger.payload)에서 읽는다.
function tapFrom(response: Notifications.NotificationResponse): NotificationTap | null {
  if (response.actionIdentifier !== Notifications.DEFAULT_ACTION_IDENTIFIER) return null;
  const { date, request } = response.notification;
  const { identifier, trigger } = request;
  const payload = trigger !== null && 'type' in trigger && trigger.type === 'push' ? trigger.payload : undefined;
  // 서버가 apns-collapse-id를 쓰면 다른 날 온 알림도 identifier가 같을 수 있어서 받은 시각을 붙인다.
  return { key: `${identifier}@${date}`, target: openedTargetFrom(payload?.target) };
}
