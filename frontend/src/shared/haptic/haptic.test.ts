import { describe, expect, it, jest } from "@jest/globals";
import { playHaptic } from "./haptic";

function appWindow(capabilities: string[]) {
  const postMessage = jest.fn<(message: string) => void>();
  return {
    bridgeWindow: { __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities }, ReactNativeWebView: { postMessage } },
    postMessage,
  };
}

describe("손 동작 진동", () => {
  it.each(["impact", "selection"] as const)("%s 진동은 빈 payload로 보낸다", (kind) => {
    const { bridgeWindow, postMessage } = appWindow([`haptic.${kind}`]);
    playHaptic(kind, bridgeWindow);
    expect(postMessage).toHaveBeenCalledWith(`{"v":1,"type":"haptic.${kind}","payload":{}}`);
  });

  it("그 진동을 모르는 옛 앱에는 보내지 않는다", () => {
    const { bridgeWindow, postMessage } = appWindow(["haptic.impact"]);
    playHaptic("selection", bridgeWindow);
    expect(postMessage).not.toHaveBeenCalled();
  });

  it("브라우저에서는 에러 없이 끝난다", () => {
    expect(() => playHaptic("selection", {})).not.toThrow();
  });
});
