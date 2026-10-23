import { describe, expect, it } from "@jest/globals";
import { isNativeMapEvent, nativeMapCapability } from "./bridge";

describe("native map capability and event boundary", () => {
  it("명시적으로 주입된 호환 iOS host만 선택한다", () => {
    expect(nativeMapCapability({})).toBeNull();
    const host = { version: 1 as const, platform: "ios" as const, postMessage: () => {} };
    expect(nativeMapCapability({ SalmonNativeMap: host })).toBe(host);
    expect(nativeMapCapability({ SalmonNativeMap: { ...host, version: 2 } as unknown as typeof host })).toBeNull();
  });

  it("다른 지도 세션과 잘못된 선택·위치 이벤트를 버린다", () => {
    const event = {
      v: 1,
      sessionId: "current",
      revision: 1,
      type: "stopPress",
      payload: { selection: { direction: "UP", sequence: 2 } },
    };
    expect(isNativeMapEvent(event, "current")).toBe(true);
    expect(isNativeMapEvent(event, "old")).toBe(false);
    expect(isNativeMapEvent({ ...event, payload: { selection: { direction: "SIDE", sequence: 2 } } }, "current")).toBe(
      false,
    );
    expect(
      isNativeMapEvent(
        { ...event, type: "locationResult", payload: { requestId: 1, coordinate: { latitude: 100, longitude: 127 } } },
        "current",
      ),
    ).toBe(false);
    expect(
      isNativeMapEvent({ ...event, type: "locationResult", payload: { requestId: 1, error: "denied" } }, "current"),
    ).toBe(true);
  });
});
