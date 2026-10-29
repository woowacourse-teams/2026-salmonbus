import type { HapticCommands } from "../haptic/haptic.types";
import type { NativeMapBridgeCommands, NativeMapBridgeEvents } from "../native-map/protocol";
import type { SettingsCommands } from "../settings/settings.types";

/**
 * 웹과 iOS 앱이 주고받는 메시지. type은 `<영역>.<동작>` 형식이다 (map.updateScene, haptic.impact).
 * 한 영역만 쓰는 값(지도의 sessionId와 revision, 요청과 답을 잇는 id)은 envelope가 아니라 payload에 둔다.
 */
export interface BridgeMessage {
  type: string;
  payload: object;
}

export interface BridgeEnvelope extends BridgeMessage {
  v: 1;
}

/**
 * 웹이 앱에 보내는 메시지 전체 목록. 앱 App.tsx의 CAPABILITIES_BY_DOMAIN이 받는 목록과 같아야 한다.
 * payload 값이 늘면 새 type을 만든다. 옛 앱이 새 type을 모를 때는 웹이 정한다:
 * 보조 피드백은 가까운 값으로 대신하고, 사용자가 누르는 기능과 결과가 중요한 요청은 숨긴다.
 */
export type AppCommands = NativeMapBridgeCommands & HapticCommands & SettingsCommands;
export type AppCommandType = keyof AppCommands;

/** 앱이 웹에 보내는 메시지 전체 목록. */
export type AppEvents = NativeMapBridgeEvents;
export type AppEventType = keyof AppEvents;

/** 앱이 웹보다 먼저 넣어 두는 앱 정보. capabilities는 앱이 받을 수 있는 메시지 type 목록이다. */
export interface AppHost {
  appVersion: string;
  capabilities: readonly string[];
}

declare global {
  interface Window {
    __SALMONBUS_APP__?: unknown;
    ReactNativeWebView?: { postMessage?: unknown };
  }
}
