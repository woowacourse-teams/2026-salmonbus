import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, it } from 'node:test';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import * as documentLoad from './documentLoad.ts';
import * as messages from './notificationMessages.ts';
import { createTapInbox } from './tapInbox.ts';
import { bridgeMessageFrom } from '../bridge/bridge.ts';

const TARGET = { routeId: '234000884', stopId: '203000165', direction: 'UP' };
const WEB_URL = 'https://app.salmonbus.test';
const source = ts.transpileModule(readFileSync(new URL('../App.tsx', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText;

function startApp() {
  const sent = [];
  let onTap;
  let webView;
  let currentTime = 1_000;
  const loadFailedChanges = [];
  const timers = [];
  const render = (type, props) => {
    if (type === 'WebView') {
      webView = props;
      props.ref.current = {
        injectJavaScript: (message) => sent.push(JSON.parse(message)),
        reload() {},
      };
    }
    return { type, props };
  };
  const modules = {
    react: {
      useRef: (current) => ({ current }),
      useState: (value) => [value, (next) => loadFailedChanges.push(next)],
      useEffect: (effect) => effect(),
      useEffectEvent: (handler) => handler,
    },
    'react/jsx-runtime': { jsx: render, jsxs: render },
    'expo-application': {},
    'expo-splash-screen': { preventAutoHideAsync() {}, hide() {} },
    'expo-status-bar': { StatusBar: 'StatusBar' },
    'react-native': { Linking: {}, StyleSheet: { create: (styles) => styles } },
    'react-native-webview': { WebView: 'WebView' },
    './LoadFailedView': {},
    './auth/authMessages': { AUTH_CAPABILITIES: [] },
    './auth/signInNatively': {},
    './bridge/bridge': {
      appBootstrapScriptFor: () => '',
      bridgeEventScriptFor: (message) => JSON.stringify(message),
      bridgeMessageFrom,
    },
    './haptic/playHaptic': { HAPTIC_CAPABILITIES: [] },
    './native-map/NativeRouteMap': { NativeRouteMap: null },
    './native-map/nativeMapMessages': { NATIVE_MAP_CAPABILITIES: [] },
    './navigation/navigationPolicy': { LOGIN_PROVIDER_ORIGINS: [] },
    './notification/notificationMessages': messages,
    './notification/openedNotifications': {
      showNotificationsInForeground() {},
      watchNotificationTaps: (listener) => { onTap = listener; },
    },
    './notification/registration': {},
    './notification/documentLoad': documentLoad,
    './notification/tapInbox': { createTapInbox: () => createTapInbox(() => currentTime) },
    './settings/settingsMessages': { SETTINGS_CAPABILITIES: [] },
  };
  const exports = {};
  runInNewContext(source, {
    exports,
    require: (name) => {
      assert.ok(Object.hasOwn(modules, name), `Unexpected dependency: ${name}`);
      return modules[name];
    },
    process: { env: { EXPO_PUBLIC_WEB_URL: WEB_URL } },
    URL,
    setTimeout: (run, delay) => timers.push({ run, at: currentTime + delay, cleared: false }),
    clearTimeout: (id) => {
      if (timers[id - 1]) timers[id - 1].cleared = true;
    },
  });
  exports.default();
  return {
    sent,
    tap: (key = 'n1@1') => onTap({ key, target: TARGET }),
    load: () => webView.onLoad?.({ nativeEvent: { url: WEB_URL } }),
    start: () => webView.onLoadStart(),
    fail: (event) => webView[event]({ nativeEvent: { url: WEB_URL, statusCode: 500 } }),
    terminate: () => webView.onContentProcessDidTerminate(),
    advanceTime: (duration) => {
      currentTime += duration;
      for (const timer of timers) {
        if (!timer.cleared && timer.at <= currentTime) {
          timer.cleared = true;
          timer.run();
        }
      }
    },
    loadEnd: (url = WEB_URL) => webView.onLoadEnd({ nativeEvent: { url } }),
    retryScreenShown: () => loadFailedChanges.includes(true),
    ready: (requestId = 'ready-1', url = WEB_URL) => webView.onMessage({ nativeEvent: {
      url, data: JSON.stringify({ v: 1, type: 'notification.takeOpened', payload: { requestId } }),
    } }),
  };
}

const deliveredTargets = (app) => app.sent.map((message) => message.payload.target).filter(Boolean);

describe('앱 WebView의 알림 전달', () => {
  it('문서만 뜨고 준비되지 않으면 보관하고 다시 로드해 준비되면 한 번 준다', () => {
    const app = startApp();
    app.start();
    app.tap();
    app.load();
    assert.deepEqual(app.sent, []);
    app.start();
    app.load();
    app.ready();
    app.ready('ready-2');
    assert.deepEqual(deliveredTargets(app), [TARGET]);
    assert.equal(app.sent[0].payload.requestId, 'ready-1');
    assert.equal(app.sent[1].payload.target, null);
  });

  for (const event of ['onHttpError', 'onError']) {
    it(`${event} 뒤 다시 로드해도 보관한 탭이 한 번 간다`, () => {
      const app = startApp();
      app.start();
      app.tap();
      app.fail(event);
      app.load();
      app.ready();
      assert.deepEqual(deliveredTargets(app), []);
      app.start();
      app.ready('retry-1');
      app.load();
      assert.deepEqual(deliveredTargets(app), [TARGET]);
    });
  }

  it('onLoad 전이라도 준비 신호를 받은 문서는 새 탭을 바로 보내고 중복은 버린다', () => {
    const app = startApp();
    app.start();
    app.ready();
    app.tap();
    app.tap();
    assert.deepEqual(deliveredTargets(app), [TARGET]);
    app.load();
    assert.deepEqual(deliveredTargets(app), [TARGET]);
    assert.equal(app.sent[1].payload.requestId, null);
  });

  for (const reset of ['start', 'terminate', 'onHttpError', 'onError']) {
    it(`${reset}에서 준비 상태를 내리고 다음 준비 신호까지 보관한다`, () => {
      const app = startApp();
      app.start();
      app.ready();
      if (reset.startsWith('on')) app.fail(reset);
      else app[reset]();
      app.tap();
      app.load();
      assert.deepEqual(deliveredTargets(app), []);
      if (reset.startsWith('on')) app.start();
      app.ready('next-1');
      assert.deepEqual(deliveredTargets(app), [TARGET]);
    });
  }

  it('10분이 지난 탭은 준비 신호에 답할 때 버린다', () => {
    const app = startApp();
    app.start();
    app.tap();
    app.advanceTime(10 * 60 * 1_000);
    app.ready();
    assert.deepEqual(deliveredTargets(app), []);
    assert.equal(app.sent[0].payload.target, null);
  });

  it('앱 밖 문서나 잘못된 요청은 준비 신호로 인정하지 않는다', () => {
    const app = startApp();
    app.start();
    app.ready('outside-1', 'https://other.test');
    app.ready('');
    app.tap();
    assert.deepEqual(app.sent, []);
    app.ready();
    assert.deepEqual(deliveredTargets(app), [TARGET]);
  });
});

describe('준비 신호가 오지 않는 문서', () => {
  it('보관한 탭이 있는데 문서가 다 뜬 뒤 10초 동안 준비 신호가 없으면 다시 시도 화면을 띄운다', () => {
    const app = startApp();
    app.start();
    app.tap();
    app.loadEnd();
    app.advanceTime(9_999);
    assert.equal(app.retryScreenShown(), false);
    app.advanceTime(1);
    assert.equal(app.retryScreenShown(), true);
  });

  it('다시 시도 화면에서 다시 읽은 문서가 준비되면 보관한 탭이 한 번 간다', () => {
    const app = startApp();
    app.start();
    app.tap();
    app.loadEnd();
    app.advanceTime(10_000);
    app.start();
    app.ready('retry-1');
    assert.deepEqual(deliveredTargets(app), [TARGET]);
  });

  it('준비 신호가 오면 다시 시도 화면을 띄우지 않는다', () => {
    const app = startApp();
    app.start();
    app.tap();
    app.loadEnd();
    app.ready();
    app.advanceTime(10_000);
    assert.equal(app.retryScreenShown(), false);
    assert.deepEqual(deliveredTargets(app), [TARGET]);
  });

  it('보관한 탭이 없거나 앱 밖 문서면 기다리지 않는다', () => {
    const app = startApp();
    app.start();
    app.loadEnd();
    app.tap();
    app.loadEnd('https://kauth.kakao.com/oauth');
    app.advanceTime(10_000);
    assert.equal(app.retryScreenShown(), false);
  });

  it('새 문서를 읽기 시작하면 이전 문서의 기다림을 멈춘다', () => {
    const app = startApp();
    app.start();
    app.tap();
    app.loadEnd();
    app.advanceTime(5_000);
    app.start();
    app.advanceTime(5_000);
    assert.equal(app.retryScreenShown(), false);
  });
});
