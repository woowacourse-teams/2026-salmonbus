import { describe, expect, it, jest } from "@jest/globals";
import { BRIDGE_EVENT } from "../bridge/bridge";
import type { NotificationTarget } from "./notification.types";
import {
  createOpenedNotificationInbox,
  initOpenedNotifications,
  subscribeToOpenedNotifications,
} from "./openedNotificationInbox";

const TARGET: NotificationTarget = { routeId: "234000884", stopId: "203000165", direction: "UP" };
const LATER_TARGET: NotificationTarget = { routeId: "234000026", stopId: "203000165", direction: "DOWN" };

function opened(target: EventTarget, payload: object) {
  target.dispatchEvent(new CustomEvent(BRIDGE_EVENT, { detail: { v: 1, type: "notification.opened", payload } }));
}

describe("알림 대상 보관", () => {
  it("듣는 쪽이 없을 때 온 마지막 대상 하나를 첫 구독자에게 한 번만 넘긴다", () => {
    const inbox = createOpenedNotificationInbox();
    inbox.receive(TARGET);
    inbox.receive(LATER_TARGET);

    const first = jest.fn<(target: NotificationTarget) => void>();
    const second = jest.fn<(target: NotificationTarget) => void>();
    inbox.subscribe(first);
    inbox.subscribe(second);

    expect(first.mock.calls).toEqual([[LATER_TARGET]]);
    expect(second).not.toHaveBeenCalled();
  });

  it("듣는 쪽이 있으면 보관하지 않고 바로 넘긴다", () => {
    const inbox = createOpenedNotificationInbox();
    const listener = jest.fn<(target: NotificationTarget) => void>();
    const unsubscribe = inbox.subscribe(listener);
    inbox.receive(TARGET);
    unsubscribe();

    const late = jest.fn<(target: NotificationTarget) => void>();
    inbox.subscribe(late);
    expect(listener.mock.calls).toEqual([[TARGET]]);
    expect(late).not.toHaveBeenCalled();
  });
});

describe("웹 앱 시작 때 듣기", () => {
  it("화면이 구독하기 전에 켜진 앱에서 온 대상을 잃지 않는다", () => {
    const appEvents = new EventTarget();
    const stop = initOpenedNotifications(appEvents);
    opened(appEvents, { requestId: null, target: TARGET });

    const screen = jest.fn<(target: NotificationTarget) => void>();
    const unsubscribe = subscribeToOpenedNotifications(screen);
    unsubscribe();
    stop();

    expect(screen.mock.calls).toEqual([[TARGET]]);
  });

  it("takeOpened의 답과 대상이 없는 탭은 보관하지 않는다", () => {
    const appEvents = new EventTarget();
    const stop = initOpenedNotifications(appEvents);
    opened(appEvents, { requestId: "r", target: TARGET });
    opened(appEvents, { requestId: null, target: null });
    opened(appEvents, { requestId: null, target: { ...TARGET, direction: "LEFT" } });

    const screen = jest.fn<(target: NotificationTarget) => void>();
    const unsubscribe = subscribeToOpenedNotifications(screen);
    unsubscribe();
    stop();

    expect(screen).not.toHaveBeenCalled();
  });
});
