import { useNotificationRegistration } from "@/shared/notification/useNotificationRegistration";
import { useOpenedNotification } from "@/shared/notification/useOpenedNotification";

export function AppNotifications() {
  useNotificationRegistration();
  useOpenedNotification();
  return null;
}
