import type { HapticCommands } from "../haptic/haptic.types";
import type { AuthCommands, AuthEvents } from "../native-auth/nativeAuth.types";
import type { NativeMapBridgeCommands, NativeMapBridgeEvents } from "../native-map/protocol";
import type { SettingsCommands } from "../settings/settings.types";

export interface BridgeMessage {
  type: string;
  payload: object;
}

export interface BridgeEnvelope extends BridgeMessage {
  v: 1;
}

export type AppCommands = NativeMapBridgeCommands & HapticCommands & SettingsCommands & AuthCommands;
export type AppCommandType = keyof AppCommands;

export type AppEvents = NativeMapBridgeEvents & AuthEvents;
export type AppEventType = keyof AppEvents;

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
