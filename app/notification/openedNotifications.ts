import * as Notifications from 'expo-notifications';
import { openedTargetFrom } from './notificationMessages';
import type { NotificationTap } from './tapInbox';

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

function tapFrom(response: Notifications.NotificationResponse): NotificationTap | null {
  if (response.actionIdentifier !== Notifications.DEFAULT_ACTION_IDENTIFIER) return null;
  const { date, request } = response.notification;
  const { identifier, trigger } = request;
  const payload = trigger !== null && 'type' in trigger && trigger.type === 'push' ? trigger.payload : undefined;
  return { key: `${identifier}@${date}`, target: openedTargetFrom(payload?.target) };
}
