import { describe, expect, it, jest } from "@jest/globals";
import type { NotificationTarget } from "./notification.types";
import { createOpenedNotificationInbox } from "./openedNotificationInbox";
import { boardEntryFrom } from "../routing/boardEntry";
import { followOpenedNotifications, openNotificationBoard, takeOnce } from "./useOpenedNotification";

const TARGET: NotificationTarget = { routeId: "234000884", stopId: "203000165", direction: "UP" };
const RUNNING_TARGET: NotificationTarget = { routeId: "234000026", stopId: "203000165", direction: "DOWN" };

function sourcesFor(launchTarget: NotificationTarget | null) {
  const inbox = createOpenedNotificationInbox();
  const take = jest.fn<() => Promise<NotificationTarget | null>>().mockResolvedValue(launchTarget);
  return { inbox, take, sources: { takeLaunchTarget: takeOnce(take), subscribe: inbox.subscribe } };
}

const settled = () => new Promise((resolve) => setTimeout(resolve, 0));

describe("알림으로 연 대상 따라가기", () => {
  it("알림을 눌러 앱을 열었으면 그 대상을 연다", async () => {
    const { sources } = sourcesFor(TARGET);
    const open = jest.fn<(target: NotificationTarget) => void>();
    followOpenedNotifications(open, sources);
    await settled();
    expect(open.mock.calls).toEqual([[TARGET]]);
  });

  it("꺼낸 대상이 없으면 아무것도 열지 않는다", async () => {
    const { sources } = sourcesFor(null);
    const open = jest.fn<(target: NotificationTarget) => void>();
    followOpenedNotifications(open, sources);
    await settled();
    expect(open).not.toHaveBeenCalled();
  });

  it("effect가 두 번 돌아도 앱에서 한 번만 꺼내고 한 번만 연다", async () => {
    const { sources, take } = sourcesFor(TARGET);
    const open = jest.fn<(target: NotificationTarget) => void>();
    const stopFirst = followOpenedNotifications(open, sources);
    stopFirst();
    followOpenedNotifications(open, sources);
    await settled();
    expect(take).toHaveBeenCalledTimes(1);
    expect(open.mock.calls).toEqual([[TARGET]]);
  });

  it("앱이 켜진 동안 누른 알림은 그때마다 열고, 그만 따라간 뒤에는 열지 않는다", async () => {
    const { sources, inbox } = sourcesFor(null);
    const open = jest.fn<(target: NotificationTarget) => void>();
    const stop = followOpenedNotifications(open, sources);
    inbox.receive(RUNNING_TARGET);
    stop();
    inbox.receive(TARGET);
    await settled();
    expect(open.mock.calls).toEqual([[RUNNING_TARGET]]);
  });
});

describe("알림 대상의 판정 보드 열기", () => {
  it("그 정류장의 판정 보드로 가고, 판정 보드가 읽는 진입 경로는 notification이다", () => {
    const navigate = jest.fn<(path: string, options: { state: unknown }) => void>();
    const trackEvent = jest.fn();

    openNotificationBoard(TARGET, navigate, trackEvent);

    expect(navigate).toHaveBeenCalledTimes(1);
    const [path, { state }] = navigate.mock.calls[0]!;
    expect(path).toBe("/routes/234000884?direction=UP&stopId=203000165");
    expect(boardEntryFrom(state)).toBe("notification");
    expect(trackEvent).toHaveBeenCalledWith("notification_opened", {
      route_id: "234000884",
      direction: "UP",
      stop_id: "203000165",
    });
  });
});
