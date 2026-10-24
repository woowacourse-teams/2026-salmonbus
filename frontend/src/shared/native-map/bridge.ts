import { isSupportedInApp, sendToApp, subscribeToApp, type BridgeWindow } from "../bridge/bridge";
import type { AppCommands, AppCommandType, BridgeMessage } from "../bridge/bridge.types";
import type { NativeMapCommands, NativeMapEvent, NativeMapEvents } from "./protocol";

// NativeMapCommands에 명령이 늘면 여기서 tsc가 막는다. 앱의 app/native-map/nativeMapMessages.ts 목록도 같이 늘린다.
const nativeMapCommandTypes = {
  mount: true,
  updateScene: true,
  motion: true,
  layout: true,
  camera: true,
  location: true,
  unmount: true,
} satisfies Record<keyof NativeMapCommands, true>;

/** native 지도를 쓰려면 앱이 받아야 하는 메시지 전체. 테스트의 가짜 앱도 이 목록을 쓴다. */
export const NATIVE_MAP_CAPABILITIES: readonly AppCommandType[] = (
  Object.keys(nativeMapCommandTypes) as (keyof NativeMapCommands)[]
).map((type) => `map.${type}` as const);

// 지도 명령을 전부 받는 앱에서만 native 지도를 사용하고, 브라우저와 옛 앱은 JS SDK를 사용한다.
export function isNativeMapAvailable(bridgeWindow?: BridgeWindow): boolean {
  return NATIVE_MAP_CAPABILITIES.every((type) => isSupportedInApp(type, bridgeWindow));
}

export interface NativeMapChannel {
  readonly sessionId: string;
  send: <T extends keyof NativeMapCommands>(type: T, payload: NativeMapCommands[T]) => number;
  subscribe: (listener: (event: NativeMapEvent) => void) => () => void;
  destroy: () => void;
}

export function createNativeMapChannel(bridgeWindow: BridgeWindow & EventTarget = window): NativeMapChannel {
  const sessionId =
    typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, "0")).join("");
  const listeners = new Set<(event: NativeMapEvent) => void>();
  let revision = 0;
  let destroyed = false;
  const unsubscribeFromApp = subscribeToApp((message) => {
    if (destroyed) return;
    const event = nativeMapEventFrom(message, sessionId);
    if (event !== null) listeners.forEach((listener) => listener(event));
  }, bridgeWindow);
  return {
    sessionId,
    send(type, payload) {
      if (destroyed) return revision;
      revision += 1;
      // TypeScript는 `map.${T}`와 AppCommands의 키를 이어 주지 못해서, 같은 모양임을 여기서 한 번만 알려 준다.
      const command = { ...payload, sessionId, revision } as AppCommands[`map.${typeof type}`];
      sendToApp(`map.${type}`, command, bridgeWindow);
      return revision;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    destroy() {
      destroyed = true;
      listeners.clear();
      unsubscribeFromApp();
    },
  };
}

type Payload = Record<string, unknown>;

function record(value: unknown): value is Payload {
  return value !== null && typeof value === "object";
}
function coordinate(value: unknown): boolean {
  return (
    record(value) &&
    typeof value.latitude === "number" &&
    Number.isFinite(value.latitude) &&
    Math.abs(value.latitude) <= 90 &&
    typeof value.longitude === "number" &&
    Number.isFinite(value.longitude) &&
    Math.abs(value.longitude) <= 180
  );
}
function selection(value: unknown): boolean {
  return record(value) && (value.direction === "UP" || value.direction === "DOWN") && Number.isInteger(value.sequence);
}

const isValidPayloadFor = {
  ready: () => true,
  mapPress: () => true,
  ack: (payload) => Number.isInteger(payload.appliedRevision),
  stopPress: (payload) => selection(payload.selection),
  vehiclePress: (payload) => selection(payload.selection) && typeof payload.key === "string",
  locationResult: (payload) =>
    Number.isInteger(payload.requestId) &&
    (coordinate(payload.coordinate) || ["denied", "timeout", "unavailable"].includes(String(payload.error))),
  error: (payload) => typeof payload.message === "string",
} satisfies Record<keyof NativeMapEvents, (payload: Payload) => boolean>;

function isNativeMapEventType(type: string): type is keyof NativeMapEvents {
  return Object.hasOwn(isValidPayloadFor, type);
}

/** 지금 지도 세션이 보낸 map 메시지만 지도 이벤트로 바꾼다. 다른 영역이나 지난 세션의 메시지는 null이다. */
export function nativeMapEventFrom(message: BridgeMessage, sessionId: string): NativeMapEvent | null {
  if (!message.type.startsWith("map.")) return null;
  const payload = message.payload as Payload;
  if (payload.sessionId !== sessionId || !Number.isInteger(payload.revision)) return null;
  const type = message.type.slice("map.".length);
  if (!isNativeMapEventType(type) || !isValidPayloadFor[type](payload)) return null;
  return { type, payload } as NativeMapEvent;
}
