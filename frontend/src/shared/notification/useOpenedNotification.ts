import { useEffect, useEffectEvent } from "react";
import { useNavigate } from "react-router";
import { track } from "../analytics/track";
import { boardEntryStateFor } from "../routing/boardEntry";
import { favoritePath } from "../routing/paths";
import { takeOpenedNotification } from "./nativeNotification";
import type { NotificationTarget } from "./notification.types";
import { subscribeToOpenedNotifications } from "./openedNotificationInbox";

type OpenTarget = (target: NotificationTarget) => void;

interface OpenedNotificationSources {
  takeLaunchTarget: () => Promise<NotificationTarget | null>;
  subscribe: (listener: OpenTarget) => () => void;
}

/** 첫 호출에만 take를 부르고, 이후 호출은 그 답을 같이 본다. */
export function takeOnce(take: () => Promise<NotificationTarget | null>): () => Promise<NotificationTarget | null> {
  let taken: Promise<NotificationTarget | null> | null = null;
  return () => (taken ??= take());
}

// 앱은 꺼내 준 대상을 지운다. StrictMode가 effect를 다시 돌려도 두 번째 요청이 빈 답을 받지 않게 페이지마다 한 번만 꺼낸다.
const takeLaunchTarget = takeOnce(() => takeOpenedNotification());

/** 알림을 눌러 앱을 연 대상과, 앱이 켜진 동안 누른 알림의 대상을 open에 넘긴다. 돌려준 함수를 부른 뒤에는 넘기지 않는다. */
export function followOpenedNotifications(
  open: OpenTarget,
  sources: OpenedNotificationSources = { takeLaunchTarget, subscribe: subscribeToOpenedNotifications },
): () => void {
  let following = true;
  void sources.takeLaunchTarget().then((target) => {
    if (following && target !== null) open(target);
  });
  const unsubscribe = sources.subscribe(open);
  return () => {
    following = false;
    unsubscribe();
  };
}

type Navigate = (path: string, options: { state: unknown }) => void;

/** 알림 대상의 판정 보드로 가면서, 판정 보드가 board_viewed에 진입 경로 notification을 남기게 한다. */
export function openNotificationBoard(
  target: NotificationTarget,
  navigate: Navigate,
  trackEvent: typeof track = track,
): void {
  navigate(favoritePath(target), { state: boardEntryStateFor("notification") });
  trackEvent("notification_opened", { route_id: target.routeId, direction: target.direction, stop_id: target.stopId });
}

/** 알림 대상의 판정 보드를 연다. 로그인하지 않아도 판정 보드는 열린다. */
export function useOpenedNotification() {
  const navigate = useNavigate();
  const openBoard = useEffectEvent((target: NotificationTarget) => openNotificationBoard(target, navigate));
  useEffect(() => followOpenedNotifications((target) => openBoard(target)), []);
}
