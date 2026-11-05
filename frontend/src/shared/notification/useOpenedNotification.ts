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

export function takeOnce(take: () => Promise<NotificationTarget | null>): () => Promise<NotificationTarget | null> {
  let taken: Promise<NotificationTarget | null> | null = null;
  return () => (taken ??= take());
}

const takeLaunchTarget = takeOnce(() => takeOpenedNotification());

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

export function openNotificationBoard(
  target: NotificationTarget,
  navigate: Navigate,
  trackEvent: typeof track = track,
): void {
  navigate(favoritePath(target), { state: boardEntryStateFor("notification") });
  trackEvent("notification_opened", { route_id: target.routeId, direction: target.direction, stop_id: target.stopId });
}

export function useOpenedNotification() {
  const navigate = useNavigate();
  const openBoard = useEffectEvent((target: NotificationTarget) => openNotificationBoard(target, navigate));
  useEffect(() => followOpenedNotifications((target) => openBoard(target)), []);
}
