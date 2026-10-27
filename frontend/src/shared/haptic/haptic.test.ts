import { describe, expect, it, jest } from "@jest/globals";
import { playImpactHaptic } from "./haptic";

function appWindow(capabilities: string[]) {
  const postMessage = jest.fn<(message: string) => void>();
  return {
    bridgeWindow: { __SALMONBUS_APP__: { appVersion: "1.0.0", capabilities }, ReactNativeWebView: { postMessage } },
    postMessage,
  };
}

describe("진동", () => {
  // 앱의 app/haptic/hapticMessages.ts가 받는 세기와 같아야 한다.
  it.each(["light", "medium"] as const)("haptic.impact를 받는 앱에는 %s 세기를 보낸다", (style) => {
    const { bridgeWindow, postMessage } = appWindow(["haptic.impact"]);
    playImpactHaptic(style, bridgeWindow);
    expect(postMessage).toHaveBeenCalledWith(`{"v":1,"type":"haptic.impact","payload":{"style":"${style}"}}`);
  });

  it("haptic을 모르는 옛 앱에는 보내지 않는다", () => {
    const { bridgeWindow, postMessage } = appWindow(["map.mount"]);
    playImpactHaptic("medium", bridgeWindow);
    expect(postMessage).not.toHaveBeenCalled();
  });

  it("브라우저에서는 에러 없이 끝난다", () => {
    expect(() => playImpactHaptic("light", {})).not.toThrow();
  });
});
