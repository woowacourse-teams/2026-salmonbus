import { sendToApp, type BridgeWindow } from "../bridge/bridge";
import type { GestureHapticKind } from "./haptic.types";

/**
 * 손 동작 진동을 울린다. 이벤트 핸들러 안, 값이 실제로 바뀐 분기에서 부른다.
 * 브라우저와 그 진동을 모르는 옛 앱에서는 아무 일도 하지 않는다.
 */
export function playHaptic(kind: GestureHapticKind, bridgeWindow?: BridgeWindow): void {
  sendToApp(`haptic.${kind}`, {}, bridgeWindow);
}
