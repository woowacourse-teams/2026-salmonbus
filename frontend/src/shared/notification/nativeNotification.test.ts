import { afterEach, describe, expect, it, jest } from "@jest/globals";
import { BRIDGE_EVENT } from "../bridge/bridge";
import {
  getNotificationRegistration,
  isNativeNotificationSupported,
  notificationRegistrationFrom,
  notificationTargetFrom,
  openedNotificationFrom,
  requestNotificationPermission,
  takeOpenedNotification,
} from "./nativeNotification";

const NOTIFICATION_COMMANDS = [
  "notification.requestPermission",
  "notification.getRegistration",
  "notification.takeOpened",
];
const TOKEN = "a1b2c3d4".repeat(8);
const TARGET = { routeId: "234000884", stopId: "203000165", direction: "UP" };

function inAppWindow(capabilities: string[] = NOTIFICATION_COMMANDS) {
  const postMessage = jest.fn<(message: string) => void>();
  return {
    bridgeWindow: { __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities }, ReactNativeWebView: { postMessage } },
    postMessage,
  };
}

function sentRequest(postMessage: jest.Mock<(message: string) => void>) {
  const [message] = postMessage.mock.calls.at(-1) ?? ["null"];
  return JSON.parse(message) as { type: string; payload: { requestId: string } };
}

function reply(target: EventTarget, type: string, payload: object) {
  target.dispatchEvent(new CustomEvent(BRIDGE_EVENT, { detail: { v: 1, type, payload } }));
}

afterEach(() => {
  jest.useRealTimers();
});

describe("알림 지원 판별", () => {
  it("앱이 notification 명령을 받을 때만 지원한다", () => {
    expect(isNativeNotificationSupported(inAppWindow().bridgeWindow)).toBe(true);
    expect(isNativeNotificationSupported(inAppWindow(["auth.signIn"]).bridgeWindow)).toBe(false);
    expect(isNativeNotificationSupported({})).toBe(false);
  });
});

describe("권한 요청과 등록 상태", () => {
  it("권한을 묻고 같은 requestId로 온 등록 상태만 받는다", async () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    const target = new EventTarget();
    const permission = requestNotificationPermission(bridgeWindow, target);
    const request = sentRequest(postMessage);
    expect(request.type).toBe("notification.requestPermission");

    reply(target, "notification.registration", {
      requestId: "other",
      status: "denied",
      token: null,
      environment: null,
    });
    reply(target, "notification.registration", {
      requestId: request.payload.requestId,
      status: "granted",
      token: TOKEN,
      environment: "PRODUCTION",
    });

    await expect(permission).resolves.toEqual({
      requestId: request.payload.requestId,
      status: "granted",
      token: TOKEN,
      environment: "PRODUCTION",
    });
  });

  it("상태 읽기는 notification.getRegistration으로 보낸다", async () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    const target = new EventTarget();
    const registration = getNotificationRegistration(bridgeWindow, target);
    const request = sentRequest(postMessage);
    expect(request.type).toBe("notification.getRegistration");

    reply(target, "notification.registration", {
      requestId: request.payload.requestId,
      status: "undetermined",
      token: null,
      environment: null,
    });

    await expect(registration).resolves.toEqual({
      requestId: request.payload.requestId,
      status: "undetermined",
      token: null,
      environment: null,
    });
  });

  it("브라우저나 명령을 모르는 옛 앱이면 보내지 않고 바로 null이다", async () => {
    const { bridgeWindow, postMessage } = inAppWindow(["auth.signIn"]);
    await expect(requestNotificationPermission(bridgeWindow)).resolves.toBeNull();
    await expect(getNotificationRegistration({})).resolves.toBeNull();
    expect(postMessage).not.toHaveBeenCalled();
  });

  it("앱이 답하지 않으면 시간이 지난 뒤 null이다", async () => {
    jest.useFakeTimers();
    const { bridgeWindow } = inAppWindow();
    const registration = getNotificationRegistration(bridgeWindow, new EventTarget());
    jest.advanceTimersByTime(15_000);
    await expect(registration).resolves.toBeNull();
  });
});

describe("알림 탭 대상 꺼내기", () => {
  it("같은 requestId로 온 대상을 돌려준다", async () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    const target = new EventTarget();
    const opened = takeOpenedNotification(bridgeWindow, target);
    const request = sentRequest(postMessage);
    expect(request.type).toBe("notification.takeOpened");

    reply(target, "notification.opened", { requestId: null, target: { ...TARGET, routeId: "999" } });
    reply(target, "notification.opened", { requestId: request.payload.requestId, target: TARGET });

    await expect(opened).resolves.toEqual(TARGET);
  });

  it("준비 신호를 보내기 전에 듣기 시작하므로 앱이 즉시 답해도 대상을 받는다", async () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    const target = new EventTarget();
    postMessage.mockImplementation((raw) => {
      const { payload } = JSON.parse(raw) as { payload: { requestId: string } };
      reply(target, "notification.opened", { requestId: payload.requestId, target: TARGET });
    });

    await expect(takeOpenedNotification(bridgeWindow, target)).resolves.toEqual(TARGET);
    expect(postMessage).toHaveBeenCalledTimes(1);
  });

  it("쌓인 대상이 없으면 null이다", async () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    const target = new EventTarget();
    const opened = takeOpenedNotification(bridgeWindow, target);
    reply(target, "notification.opened", { requestId: sentRequest(postMessage).payload.requestId, target: null });
    await expect(opened).resolves.toBeNull();
  });
});

describe("앱이 보낸 알림 메시지 읽기", () => {
  it("token과 environment 중 하나라도 어긋나면 둘 다 null로 읽는다", () => {
    expect(
      notificationRegistrationFrom({ requestId: "r", status: "granted", token: TOKEN, environment: "STAGING" }),
    ).toEqual({ requestId: "r", status: "granted", token: null, environment: null });
    expect(
      notificationRegistrationFrom({ requestId: "r", status: "granted", token: "", environment: "SANDBOX" }),
    ).toEqual({ requestId: "r", status: "granted", token: null, environment: null });
  });

  it.each([
    ["requestId 없음", { status: "granted", token: null, environment: null }],
    ["모르는 상태", { requestId: "r", status: "provisional", token: null, environment: null }],
  ])("%s이면 등록 상태로 읽지 않는다", (_name, payload) => {
    expect(notificationRegistrationFrom(payload)).toBeNull();
  });

  it("requestId가 문자열도 null도 아니면 버린다", () => {
    expect(openedNotificationFrom({ requestId: 3, target: TARGET })).toBeNull();
    expect(openedNotificationFrom({ target: TARGET })).toBeNull();
  });

  it.each([
    ["방향이 소문자", { ...TARGET, direction: "up" }],
    ["빈 노선", { ...TARGET, routeId: "" }],
    ["숫자 정류장", { ...TARGET, stopId: 203000165 }],
    ["배열", [TARGET]],
    ["null", null],
  ])("%s이면 대상으로 읽지 않는다", (_name, value) => {
    expect(notificationTargetFrom(value)).toBeNull();
  });
});
