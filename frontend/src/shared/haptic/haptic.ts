import { sendToApp, type BridgeWindow } from "../bridge/bridge";
import { useStillOnScreen } from "../routing/stillOnScreen";
import type { GestureHapticKind, HapticKind } from "./haptic.types";

/** 브라우저와 그 진동을 모르는 옛 앱에서는 아무 일도 하지 않는다. */
function sendHaptic(kind: HapticKind, bridgeWindow?: BridgeWindow): void {
  sendToApp(`haptic.${kind}`, {}, bridgeWindow);
}

/** 손 동작 진동을 울린다. 이벤트 핸들러 안, 값이 실제로 바뀐 분기에서 부른다. */
export function playHaptic(kind: GestureHapticKind, bridgeWindow?: BridgeWindow): void {
  sendHaptic(kind, bridgeWindow);
}

/**
 * 서버 작업의 결과 진동을 울리는 함수를 돌려준다. 성공이면 success, 실패면 error를 울리고,
 * 응답이 오기 전에 화면을 떠났으면 울리지 않는다.
 */
export function useResultHaptic(): (ok: boolean) => void {
  const isStillOnScreen = useStillOnScreen();
  return (ok) => {
    if (isStillOnScreen()) sendHaptic(ok ? "success" : "error");
  };
}
