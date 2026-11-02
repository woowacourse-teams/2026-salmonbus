/* eslint-disable @typescript-eslint/no-unused-vars -- 아래 함수는 호출용이 아니라 컴파일 검사용이라서 어디서도 쓰지 않는다 */
import type { FavoriteTarget } from "../api/favorites.types";
import type { NotificationTarget } from "./notification.types";

// notification.types.ts는 앱이 읽는 파일이라 FavoriteTarget을 import하지 않고 따로 적는다.
// 즐겨찾기 대상이 바뀌어 두 타입이 어긋나면 여기서 타입 검사가 막는다.
function notificationTargetIsFavoriteTarget(target: NotificationTarget): FavoriteTarget {
  return target;
}

function favoriteTargetIsNotificationTarget(target: FavoriteTarget): NotificationTarget {
  return target;
}
