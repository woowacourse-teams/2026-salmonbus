import type { FavoriteTarget } from "../api/favorites.types";
import type { NotificationTarget } from "./notification.types";

function notificationTargetIsFavoriteTarget(target: NotificationTarget): FavoriteTarget {
  return target;
}

function favoriteTargetIsNotificationTarget(target: FavoriteTarget): NotificationTarget {
  return target;
}
