// 웹의 frontend/src/shared/bridge/bridge.ts와 같은 형식을 쓴다. 한쪽을 바꾸면 다른 쪽과 테스트도 같이 바꾼다.
export const BRIDGE_EVENT = 'salmon:bridge';
const MESSAGE_TYPE = /^[a-z][a-zA-Z]{0,31}\.[a-z][a-zA-Z]{0,31}$/;
const MAX_MESSAGE_LENGTH = 4_000_000;

export interface BridgeMessage {
  type: string;
  payload: Record<string, unknown>;
}

export interface AppHost {
  appVersion: string;
  capabilities: readonly string[];
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

// 값을 실행 코드가 아니라 문자열 데이터로 넣는다. U+2028, U+2029도 이스케이프한다.
function scriptLiteralFrom(value: unknown): string {
  return JSON.stringify(value).replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
}

/** 웹 코드보다 먼저 실행해서 window.__SALMONBUS_APP__에 앱 정보를 넣는 스크립트. */
export function appBootstrapScriptFor(app: AppHost): string {
  const value = scriptLiteralFrom(JSON.stringify({ appVersion: app.appVersion, capabilities: app.capabilities }));
  return `
    (function () {
      var app = JSON.parse(${value});
      Object.freeze(app.capabilities);
      Object.defineProperty(window, '__SALMONBUS_APP__', { configurable: true, value: Object.freeze(app) });
    })();
    true;
  `;
}

/** 웹이나 지도 모듈이 보낸 JSON 문자열에서 envelope 형식이 맞는 메시지만 꺼낸다. */
export function bridgeMessageFrom(raw: string): BridgeMessage | null {
  if (raw.length > MAX_MESSAGE_LENGTH) return null;
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!isRecord(value) || value.v !== 1) return null;
  if (typeof value.type !== 'string' || !MESSAGE_TYPE.test(value.type)) return null;
  if (!isRecord(value.payload)) return null;
  return { type: value.type, payload: value.payload };
}

/** 메시지를 웹에 넘기는 injectJavaScript 스크립트. 웹은 salmon:bridge CustomEvent로 받는다. */
export function bridgeEventScriptFor(message: BridgeMessage): string {
  const envelope = scriptLiteralFrom(JSON.stringify({ v: 1, type: message.type, payload: message.payload }));
  return `window.dispatchEvent(new CustomEvent(${scriptLiteralFrom(BRIDGE_EVENT)}, { detail: JSON.parse(${envelope}) })); true;`;
}
