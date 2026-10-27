import { describe, expect, it, jest } from "@jest/globals";
import type { BridgeMessage } from "./bridge.types";
import { BRIDGE_EVENT, appHostFrom, bridgeMessageFrom, isSupportedInApp, sendToApp, subscribeToApp } from "./bridge";

function inAppWindow(capabilities: unknown[] = ["map.unmount"]) {
  const postMessage = jest.fn<(message: string) => void>();
  return {
    bridgeWindow: { __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities }, ReactNativeWebView: { postMessage } },
    postMessage,
  };
}

describe("앱 안 판별", () => {
  it.each([
    ["앱 정보와 postMessage가 둘 다 없을 때", {}],
    ["앱 정보만 있을 때", { __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities: [] } }],
    [
      "옛 앱처럼 앱 정보 없이 ReactNativeWebView와 SalmonNativeMap만 있을 때",
      {
        ReactNativeWebView: { postMessage: jest.fn() },
        SalmonNativeMap: { version: 1, platform: "ios", postMessage: jest.fn() },
      },
    ],
    ["appVersion이 없을 때", { ...inAppWindow().bridgeWindow, __SALMONBUS_APP__: { capabilities: [] } }],
    ["postMessage가 함수가 아닐 때", { ...inAppWindow().bridgeWindow, ReactNativeWebView: { postMessage: "x" } }],
  ])("%s 브라우저로 본다", (_name, bridgeWindow) => {
    expect(appHostFrom(bridgeWindow)).toBeNull();
  });

  it("앱 정보와 postMessage가 모두 있으면 앱 정보를 돌려준다", () => {
    expect(appHostFrom(inAppWindow().bridgeWindow)).toEqual({ appVersion: "1.0.0", capabilities: ["map.unmount"] });
  });

  it("capabilities에서 문자열이 아닌 값은 버린다", () => {
    expect(appHostFrom(inAppWindow(["map.unmount", 3, null]).bridgeWindow)?.capabilities).toEqual(["map.unmount"]);
  });

  it("capabilities에 있는 type만 지원한다고 답한다", () => {
    const { bridgeWindow } = inAppWindow();
    expect(isSupportedInApp("map.unmount", bridgeWindow)).toBe(true);
    expect(isSupportedInApp("map.mount", bridgeWindow)).toBe(false);
    expect(isSupportedInApp("map.unmount", {})).toBe(false);
  });
});

describe("앱으로 보내기", () => {
  it("지원하는 type은 v를 붙인 JSON으로 보낸다", () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    sendToApp("map.unmount", { sessionId: "s", revision: 1 }, bridgeWindow);
    expect(postMessage).toHaveBeenCalledWith('{"v":1,"type":"map.unmount","payload":{"sessionId":"s","revision":1}}');
  });

  it("capabilities에 없는 type은 보내지 않는다", () => {
    const { bridgeWindow, postMessage } = inAppWindow();
    sendToApp("map.location", { sessionId: "s", revision: 1, requestId: 1 }, bridgeWindow);
    expect(postMessage).not.toHaveBeenCalled();
  });

  it("브라우저에서는 에러 없이 끝난다", () => {
    expect(() => sendToApp("map.unmount", { sessionId: "s", revision: 1 }, {})).not.toThrow();
  });
});

describe("앱에서 받기", () => {
  it("형식이 맞는 envelope는 type과 payload만 남긴다", () => {
    expect(bridgeMessageFrom({ v: 1, type: "map.ready", payload: {} })).toEqual({ type: "map.ready", payload: {} });
    expect(bridgeMessageFrom({ v: 1, type: "notification.requestPermission", payload: {} })).not.toBeNull();
  });

  it.each([
    ["객체가 아닐 때", "{}"],
    ["v가 1이 아닐 때", { v: 2, type: "map.ready", payload: {} }],
    ["type이 비었을 때", { v: 1, type: "", payload: {} }],
    ["type에 영역이 없을 때", { v: 1, type: "ready", payload: {} }],
    ["type에 영역이 둘일 때", { v: 1, type: "map.camera.move", payload: {} }],
    ["type이 너무 길 때", { v: 1, type: `map.${"a".repeat(40)}`, payload: {} }],
    ["payload가 배열일 때", { v: 1, type: "map.ready", payload: [] }],
    ["payload가 없을 때", { v: 1, type: "map.ready" }],
  ])("%s 버린다", (_name, value) => {
    expect(bridgeMessageFrom(value)).toBeNull();
  });

  it("구독을 끊기 전까지 형식이 맞는 메시지만 listener에 넘기고, 틀린 메시지는 경고를 남긴다", () => {
    const warn = jest.spyOn(console, "warn").mockImplementation(() => {});
    const target = new EventTarget();
    const listener = jest.fn<(message: BridgeMessage) => void>();
    const unsubscribe = subscribeToApp(listener, target);
    target.dispatchEvent(new CustomEvent(BRIDGE_EVENT, { detail: { v: 1, type: "map.ready", payload: {} } }));
    target.dispatchEvent(new CustomEvent(BRIDGE_EVENT, { detail: { v: 1, type: 7, payload: {} } }));
    unsubscribe();
    target.dispatchEvent(new CustomEvent(BRIDGE_EVENT, { detail: { v: 1, type: "map.ready", payload: {} } }));
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ type: "map.ready", payload: {} });
    expect(warn).toHaveBeenCalledTimes(1);
  });
});
