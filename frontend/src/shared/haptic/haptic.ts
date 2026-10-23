import { sendToApp, type BridgeWindow } from "../bridge/bridge";
import type { HapticImpactStyle } from "./haptic.types";

/** 앱 안에서만 진동을 울린다. 브라우저와 haptic을 모르는 옛 앱에서는 아무 일도 하지 않는다. */
export function playImpactHaptic(style: HapticImpactStyle, bridgeWindow?: BridgeWindow): void {
  sendToApp("haptic.impact", { style }, bridgeWindow);
}
