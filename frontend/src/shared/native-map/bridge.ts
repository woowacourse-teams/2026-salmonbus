import type { NativeMapCommands, NativeMapEvent } from "./protocol";

export interface NativeMapCapability {
  version: 1;
  platform: "ios";
  postMessage: (message: string) => void;
}
declare global {
  interface Window {
    SalmonNativeMap?: NativeMapCapability;
  }
}

// 주입된 호환 host가 있을 때만 native 지도를 사용하고, 일반 모바일 브라우저는 JS SDK를 사용한다.
export function nativeMapCapability(host: Pick<Window, "SalmonNativeMap"> = window): NativeMapCapability | null {
  const capability = host.SalmonNativeMap;
  return capability?.version === 1 && capability.platform === "ios" && typeof capability.postMessage === "function"
    ? capability
    : null;
}

export interface NativeMapChannel {
  readonly sessionId: string;
  send: <T extends keyof NativeMapCommands>(type: T, payload: NativeMapCommands[T]) => number;
  subscribe: (listener: (event: NativeMapEvent) => void) => () => void;
  destroy: () => void;
}

export function createNativeMapChannel(capability: NativeMapCapability): NativeMapChannel {
  const sessionId =
    typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : Array.from(crypto.getRandomValues(new Uint8Array(16)), (byte) => byte.toString(16).padStart(2, "0")).join("");
  const listeners = new Set<(event: NativeMapEvent) => void>();
  let revision = 0;
  let destroyed = false;
  const receive = (event: Event) => {
    const value: unknown = (event as CustomEvent<unknown>).detail;
    if (destroyed || !isNativeMapEvent(value, sessionId)) return;
    listeners.forEach((listener) => listener(value));
  };
  window.addEventListener("salmon:native-map", receive);
  return {
    sessionId,
    send(type, payload) {
      if (destroyed) return revision;
      revision += 1;
      capability.postMessage(JSON.stringify({ v: 1, sessionId, revision, type, payload }));
      return revision;
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    destroy() {
      destroyed = true;
      listeners.clear();
      window.removeEventListener("salmon:native-map", receive);
    },
  };
}

function record(value: unknown): value is Record<string, unknown> {
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
export function isNativeMapEvent(value: unknown, sessionId: string): value is NativeMapEvent {
  if (
    !record(value) ||
    value.v !== 1 ||
    value.sessionId !== sessionId ||
    !Number.isInteger(value.revision) ||
    !record(value.payload)
  )
    return false;
  const payload = value.payload;
  switch (value.type) {
    case "ready":
    case "mapPress":
      return true;
    case "ack":
      return Number.isInteger(payload.revision);
    case "stopPress":
      return selection(payload.selection);
    case "vehiclePress":
      return selection(payload.selection) && typeof payload.key === "string";
    case "locationResult":
      return (
        Number.isInteger(payload.requestId) &&
        (coordinate(payload.coordinate) || ["denied", "timeout", "unavailable"].includes(String(payload.error)))
      );
    case "error":
      return typeof payload.message === "string";
    default:
      return false;
  }
}
