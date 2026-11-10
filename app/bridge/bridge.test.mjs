import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import vm from 'node:vm';
import { BRIDGE_EVENT, appBootstrapScriptFor, bridgeEventScriptFor, bridgeMessageFrom } from './bridge.ts';

function webWindow(origin = 'https://dev.salmonbus.com') {
  const events = [];
  const posted = [];
  const listeners = {};
  const window = {
    location: { origin },
    dispatchEvent: (event) => events.push(event),
    addEventListener: (type, listener) => (listeners[type] ??= []).push(listener),
    ReactNativeWebView: { postMessage: (message) => posted.push(JSON.parse(message)) },
  };
  const fire = (type, event) => (listeners[type] ?? []).forEach((listener) => listener(event));
  class CustomEvent {
    constructor(type, init) {
      this.type = type;
      this.detail = init.detail;
    }
  }
  return { context: vm.createContext({ window, CustomEvent, JSON, Object }), window, events, posted, fire };
}

describe('앱 정보 주입', () => {
  it('웹이 읽을 앱 정보를 고칠 수 없는 값으로 넣는다', () => {
    const { context, window } = webWindow();
    vm.runInContext(appBootstrapScriptFor({ appVersion: '1.0.0', capabilities: ['map.mount'] }), context);
    assert.deepEqual({ ...window.__SALMONBUS_APP__ }, { appVersion: '1.0.0', capabilities: ['map.mount'] });
    assert.ok(Object.isFrozen(window.__SALMONBUS_APP__));
    assert.ok(Object.isFrozen(window.__SALMONBUS_APP__.capabilities));
  });

  it('앱 버전에 따옴표나 줄 구분 문자가 있어도 코드로 실행하지 않는다', () => {
    const { context, window } = webWindow();
    const appVersion = "1'); window.hacked = true; ('\u2028";
    vm.runInContext(appBootstrapScriptFor({ appVersion, capabilities: [] }), context);
    assert.equal(window.__SALMONBUS_APP__.appVersion, appVersion);
    assert.equal(window.hacked, undefined);
  });

  it('새 문서가 시작되면 웹 코드보다 먼저 앱에 문서 시작을 알린다', () => {
    const { context, posted } = webWindow();
    vm.runInContext(appBootstrapScriptFor({ appVersion: '1.0.0', capabilities: [] }), context);
    assert.deepEqual(posted, [{ v: 1, type: 'app.documentStarted', payload: {} }]);
  });

  it('페이지 캐시에서 복원된 문서는 다시 문서 시작을 알리고 처음 뜬 pageshow에는 알리지 않는다', () => {
    const { context, posted, fire } = webWindow();
    vm.runInContext(appBootstrapScriptFor({ appVersion: '1.0.0', capabilities: [] }), context);
    fire('pageshow', { persisted: false });
    assert.equal(posted.length, 1);
    fire('pageshow', { persisted: true });
    assert.equal(posted.length, 2);
    assert.equal(posted[1].type, 'app.documentStarted');
  });
});

describe('웹이 보낸 메시지 읽기', () => {
  it('envelope 형식이 맞는 메시지에서 type과 payload만 꺼낸다', () => {
    const raw = JSON.stringify({ v: 1, type: 'map.unmount', payload: { sessionId: 's', revision: 1 } });
    assert.deepEqual(bridgeMessageFrom(raw), { type: 'map.unmount', payload: { sessionId: 's', revision: 1 } });
  });

  for (const [name, raw] of [
    ['JSON이 아닌 문자열', '{'],
    ['v가 1이 아닌 메시지', JSON.stringify({ v: 2, type: 'map.mount', payload: {} })],
    ['영역이 없는 type', JSON.stringify({ v: 1, type: 'mount', payload: {} })],
    ['payload가 배열인 메시지', JSON.stringify({ v: 1, type: 'map.mount', payload: [] })],
    ['4MB를 넘는 메시지', 'x'.repeat(4_000_001)],
  ]) {
    it(`${name}는 버린다`, () => {
      assert.equal(bridgeMessageFrom(raw), null);
    });
  }
});

describe('웹에 메시지 넘기기', () => {
  it('salmon:bridge CustomEvent의 detail로 envelope를 넘긴다', () => {
    const { context, events } = webWindow();
    const message = { type: 'map.stopPress', payload: { sessionId: 's', revision: 2, note: '\u2028' } };
    vm.runInContext(bridgeEventScriptFor(message), context);
    assert.equal(events.length, 1);
    assert.equal(events[0].type, BRIDGE_EVENT);
    assert.deepEqual(events[0].detail, { v: 1, ...message });
  });

  it('payload의 __proto__ 키는 prototype이 아니라 데이터로 남는다', () => {
    const { context, events } = webWindow();
    const payload = JSON.parse('{"__proto__": {"polluted": true}}');
    vm.runInContext(bridgeEventScriptFor({ type: 'map.error', payload }), context);
    assert.equal(events[0].detail.payload.polluted, undefined);
    assert.ok(Object.hasOwn(events[0].detail.payload, '__proto__'));
  });

  it('받을 origin을 정하면 그 origin의 문서에만 넘긴다', () => {
    const message = { type: 'auth.signInResult', payload: { id: 'a', provider: 'apple', status: 'cancelled' } };
    const ours = webWindow('https://dev.salmonbus.com');
    vm.runInContext(bridgeEventScriptFor(message, 'https://dev.salmonbus.com'), ours.context);
    assert.equal(ours.events.length, 1);

    const provider = webWindow('https://appleid.apple.com');
    vm.runInContext(bridgeEventScriptFor(message, 'https://dev.salmonbus.com'), provider.context);
    assert.equal(provider.events.length, 0);
  });
});
