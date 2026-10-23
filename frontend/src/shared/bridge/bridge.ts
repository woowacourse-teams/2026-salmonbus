import type { AppCommands, AppCommandType, AppHost, BridgeEnvelope, BridgeMessage } from "./bridge.types";

/** 앱이 injectJavaScript로 웹에 메시지를 넘길 때 발생시키는 CustomEvent 이름. 앱 코드와 같은 값이어야 한다. */
export const BRIDGE_EVENT = "salmon:bridge";
const MESSAGE_TYPE = /^[a-z][a-zA-Z]{0,31}\.[a-z][a-zA-Z]{0,31}$/;

export type BridgeWindow = Pick<Window, "__SALMONBUS_APP__" | "ReactNativeWebView">;

interface ConnectedApp extends AppHost {
  postMessage: (message: string) => void;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object";
}

function connectedAppFrom(bridgeWindow: BridgeWindow): ConnectedApp | null {
  const app = bridgeWindow.__SALMONBUS_APP__;
  const webView = bridgeWindow.ReactNativeWebView;
  if (!isRecord(app) || typeof app.appVersion !== "string" || !Array.isArray(app.capabilities)) return null;
  if (webView === undefined || typeof webView.postMessage !== "function") return null;
  const postMessage = webView.postMessage as (message: string) => void;
  return {
    appVersion: app.appVersion,
    capabilities: app.capabilities.filter((type): type is string => typeof type === "string"),
    postMessage: (message) => postMessage.call(webView, message),
  };
}

/** 앱 WebView 안이면 앱 정보를, 브라우저면 null을 돌려준다. */
export function appHostFrom(bridgeWindow: BridgeWindow = window): AppHost | null {
  const app = connectedAppFrom(bridgeWindow);
  return app === null ? null : { appVersion: app.appVersion, capabilities: app.capabilities };
}

/** 기능을 보여 줄지는 이 함수로 정한다. */
export function isSupportedInApp(type: AppCommandType, bridgeWindow: BridgeWindow = window): boolean {
  return appHostFrom(bridgeWindow)?.capabilities.includes(type) ?? false;
}

/** 브라우저이거나 옛 앱이라 받을 수 없는 type이면 아무 일도 하지 않는다. */
export function sendToApp<T extends AppCommandType>(
  type: T,
  payload: AppCommands[T],
  bridgeWindow: BridgeWindow = window,
): void {
  const app = connectedAppFrom(bridgeWindow);
  if (app === null || !app.capabilities.includes(type)) return;
  const envelope: BridgeEnvelope = { v: 1, type, payload };
  app.postMessage(JSON.stringify(envelope));
}

export function bridgeMessageFrom(value: unknown): BridgeMessage | null {
  if (!isRecord(value) || value.v !== 1) return null;
  if (typeof value.type !== "string" || !MESSAGE_TYPE.test(value.type)) return null;
  if (!isRecord(value.payload) || Array.isArray(value.payload)) return null;
  return { type: value.type, payload: value.payload };
}

/** 앱이 보낸 메시지 중 envelope 형식이 맞는 것만 listener에 넘긴다. payload 검사는 각 영역이 한다. */
export function subscribeToApp(listener: (message: BridgeMessage) => void, target: EventTarget = window): () => void {
  const receive = (event: Event) => {
    const message = bridgeMessageFrom((event as CustomEvent<unknown>).detail);
    if (message === null) {
      console.warn("앱이 보낸 bridge 메시지의 envelope 형식이 맞지 않아 버립니다");
      return;
    }
    listener(message);
  };
  target.addEventListener(BRIDGE_EVENT, receive);
  return () => target.removeEventListener(BRIDGE_EVENT, receive);
}
