import { describe, expect, it, jest } from "@jest/globals";
import { NATIVE_MAP_CAPABILITIES as APP_NATIVE_MAP_CAPABILITIES } from "../../../../app/native-map/nativeMapMessages";
import { BRIDGE_EVENT } from "../bridge/bridge";
import type { NativeMapEvent } from "./protocol";
import { NATIVE_MAP_CAPABILITIES, createNativeMapChannel, isNativeMapAvailable, nativeMapEventFrom } from "./bridge";

function appWindow(capabilities: readonly string[] = NATIVE_MAP_CAPABILITIES) {
  const postMessage = jest.fn<(message: string) => void>();
  const bridgeWindow = Object.assign(new EventTarget(), {
    __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities },
    ReactNativeWebView: { postMessage },
  });
  return { bridgeWindow, postMessage };
}

describe("native map 메시지 목록", () => {
  it("앱의 app/native-map/nativeMapMessages.ts와 같은 목록이다", () => {
    expect(NATIVE_MAP_CAPABILITIES).toEqual(APP_NATIVE_MAP_CAPABILITIES);
  });
});

describe("native map 사용 여부", () => {
  it("지도 명령을 전부 받는 앱에서만 native 지도를 쓴다", () => {
    expect(isNativeMapAvailable({})).toBe(false);
    expect(isNativeMapAvailable(appWindow().bridgeWindow)).toBe(true);
    const withoutLocation = NATIVE_MAP_CAPABILITIES.filter((type) => type !== "map.location");
    expect(isNativeMapAvailable(appWindow(withoutLocation).bridgeWindow)).toBe(false);
  });
});

describe("native map 채널", () => {
  it("명령을 map 영역 메시지로 보내고, 세션 값은 payload에 싣는다", () => {
    const { bridgeWindow, postMessage } = appWindow();
    const channel = createNativeMapChannel(bridgeWindow);
    channel.send("location", { requestId: 3 });
    expect(postMessage).toHaveBeenCalledTimes(1);
    expect(JSON.parse(postMessage.mock.calls[0]?.[0] ?? "")).toEqual({
      v: 1,
      type: "map.location",
      payload: { requestId: 3, sessionId: channel.sessionId, revision: 1 },
    });
    channel.destroy();
  });

  it("지금 세션의 이벤트만 구독자에게 넘기고, destroy 뒤에는 넘기지 않는다", () => {
    const { bridgeWindow } = appWindow();
    const channel = createNativeMapChannel(bridgeWindow);
    const listener = jest.fn<(event: NativeMapEvent) => void>();
    channel.subscribe(listener);
    const dispatch = (sessionId: string) =>
      bridgeWindow.dispatchEvent(
        new CustomEvent(BRIDGE_EVENT, { detail: { v: 1, type: "map.mapPress", payload: { sessionId, revision: 1 } } }),
      );
    dispatch("old");
    dispatch(channel.sessionId);
    channel.destroy();
    dispatch(channel.sessionId);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener).toHaveBeenCalledWith({ type: "mapPress", payload: { sessionId: channel.sessionId, revision: 1 } });
  });
});

describe("native map 이벤트 경계", () => {
  const session = { sessionId: "current", revision: 1 };
  const stopPress = { type: "map.stopPress", payload: { ...session, selection: { direction: "UP", sequence: 2 } } };

  it("지금 세션의 map 메시지를 영역 이름을 뗀 지도 이벤트로 바꾼다", () => {
    expect(nativeMapEventFrom(stopPress, "current")).toEqual({ type: "stopPress", payload: stopPress.payload });
  });

  it.each([
    ["다른 지도 세션", stopPress, "old"],
    ["다른 영역", { ...stopPress, type: "haptic.stopPress" }, "current"],
    ["revision이 없는 메시지", { ...stopPress, payload: { ...stopPress.payload, revision: "1" } }, "current"],
    ["모르는 지도 이벤트", { type: "map.toString", payload: session }, "current"],
    [
      "잘못된 방향의 선택",
      { type: "map.stopPress", payload: { ...session, selection: { direction: "SIDE", sequence: 2 } } },
      "current",
    ],
    [
      "범위 밖 좌표",
      {
        type: "map.locationResult",
        payload: { ...session, requestId: 1, coordinate: { latitude: 100, longitude: 127 } },
      },
      "current",
    ],
    ["적용한 revision이 없는 ack", { type: "map.ack", payload: session }, "current"],
  ])("%s는 버린다", (_name, message, sessionId) => {
    expect(nativeMapEventFrom(message, sessionId)).toBeNull();
  });

  it("권한 거부처럼 오류로 끝난 위치 결과는 받는다", () => {
    expect(
      nativeMapEventFrom(
        { type: "map.locationResult", payload: { ...session, requestId: 1, error: "denied" } },
        "current",
      ),
    ).not.toBeNull();
  });
});
