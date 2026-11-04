import { useEffect, useEffectEvent, useRef, useState } from 'react';
import * as Application from 'expo-application';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { Linking, StyleSheet } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import type { ShouldStartLoadRequest, WebViewNavigationEvent } from 'react-native-webview/lib/WebViewTypes';
import { LoadFailedView } from './LoadFailedView';
import { AUTH_CAPABILITIES, SIGN_IN_RESULT, signInRequestFrom, type SignInResult } from './auth/authMessages';
import { signInNatively } from './auth/signInNatively';
import { appBootstrapScriptFor, bridgeEventScriptFor, bridgeMessageFrom, type BridgeMessage } from './bridge/bridge';
import { HAPTIC_CAPABILITIES, playHaptic } from './haptic/playHaptic';
import { NativeRouteMap, type NativeRouteMapRef } from './native-map/NativeRouteMap';
import { NATIVE_MAP_CAPABILITIES } from './native-map/nativeMapMessages';
import { LOGIN_PROVIDER_ORIGINS, navigationDecisionFor } from './navigation/navigationPolicy';
import type { NotificationCommandType } from '../frontend/src/shared/notification/notification.types';
import {
  NOTIFICATION_CAPABILITIES,
  OPENED_EVENT,
  REGISTRATION_EVENT,
  notificationRequestIdFrom,
} from './notification/notificationMessages';
import { showNotificationsInForeground, watchNotificationTaps } from './notification/openedNotifications';
import { registrationFor } from './notification/registration';
import { createTapInbox, type NotificationTap } from './notification/tapInbox';
import { SETTINGS_CAPABILITIES } from './settings/settingsMessages';

SplashScreen.preventAutoHideAsync();
showNotificationsInForeground();

function readWebUrl() {
  const webUrl = process.env.EXPO_PUBLIC_WEB_URL;
  if (!webUrl) {
    throw new Error('EXPO_PUBLIC_WEB_URL 값이 없습니다. app/.env에 웹 주소를 넣어 주세요.');
  }
  return webUrl;
}

const WEB_URL = readWebUrl();
const WEB_ORIGIN = new URL(WEB_URL).origin;
const IN_APP_ORIGINS = [WEB_ORIGIN];
const NAVIGABLE_ORIGINS = [...IN_APP_ORIGINS, ...LOGIN_PROVIDER_ORIGINS];
// 모든 이동이 allowNavigation에 오게 한다. 허용 판단은 navigationDecisionFor가 origin을 정확히 비교해서 한다.
const EVERY_URL = ['*'];
const SPLASH_BACKGROUND_COLOR = '#E8533F';
// 영역 키와 그 영역 메시지의 type 앞부분이 다르면 tsc가 막는다 (map 키에는 map.* 만).
type DomainCapabilities<T> = { [K in keyof T]: readonly `${K & string}.${string}`[] };
function domainCapabilitiesOf<T extends DomainCapabilities<T>>(capabilities: T): T {
  return capabilities;
}

// 영역마다 앱이 받는 메시지 목록. 처리 함수(handlersByDomain)도 같은 키로 둔다.
const CAPABILITIES_BY_DOMAIN = domainCapabilitiesOf({
  map: NativeRouteMap !== null ? NATIVE_MAP_CAPABILITIES : [],
  haptic: HAPTIC_CAPABILITIES,
  settings: SETTINGS_CAPABILITIES,
  auth: AUTH_CAPABILITIES,
  notification: NOTIFICATION_CAPABILITIES,
});
type BridgeDomain = keyof typeof CAPABILITIES_BY_DOMAIN;
const APP_CAPABILITIES: readonly string[] = Object.values(CAPABILITIES_BY_DOMAIN).flat();
const APP_BOOTSTRAP = appBootstrapScriptFor({
  appVersion: Application.nativeApplicationVersion ?? 'unknown',
  capabilities: APP_CAPABILITIES,
});
const notificationTaps = createTapInbox();

function isInAppUrl(url: string): boolean {
  try {
    return IN_APP_ORIGINS.includes(new URL(url).origin);
  } catch {
    return false;
  }
}

export default function App() {
  const webViewRef = useRef<WebView>(null);
  const nativeMapRef = useRef<NativeRouteMapRef>(null);
  const commandQueue = useRef(Promise.resolve());
  const documentEpoch = useRef(0);
  const signingIn = useRef(false);
  // 웹 문서가 다 떴는지. 뜨는 중에 넣은 injectJavaScript는 사라질 수 있어서 알림 탭을 보낼지 쌓을지 이 값으로 정한다.
  const documentLoaded = useRef(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const showLoadFailed = () => {
    documentLoaded.current = false;
    resetNativeMap();
    setLoadFailed(true);
    SplashScreen.hide();
  };
  const retryLoad = () => setLoadFailed(false);
  // 백그라운드에서 WebView 프로세스가 끝나면 문서도 없다. 다시 뜨기 전에 온 알림 탭은 쌓아 둔다.
  const reloadWebView = () => {
    documentLoaded.current = false;
    webViewRef.current?.reload();
  };

  function handleLoadStart() {
    documentLoaded.current = false;
    resetNativeMap();
  }

  // WebView는 로그인 제공자 화면도 연다. 그 화면에는 알림 탭을 넘기지 않으니 웹 문서가 떴을 때만 true다.
  // 웹은 시작 지점에서 notification.opened를 듣고 있어서, 뜨는 동안 쌓인 탭은 다 뜬 순간 보낸다.
  function handleLoad(event: WebViewNavigationEvent) {
    documentLoaded.current = isInAppUrl(event.nativeEvent.url);
    if (!documentLoaded.current) return;
    const heldTap = notificationTaps.releaseHeld();
    if (heldTap !== null) sendToWeb({ type: OPENED_EVENT, payload: { requestId: null, target: heldTap.target } });
  }

  function resetNativeMap() {
    documentEpoch.current += 1;
    commandQueue.current = commandQueue.current
      .catch(() => undefined)
      .then(() => nativeMapRef.current?.reset());
  }

  function handleMessage(event: WebViewMessageEvent) {
    if (!isInAppUrl(event.nativeEvent.url)) return;
    const raw = event.nativeEvent.data;
    const message = bridgeMessageFrom(raw);
    if (message === null || !APP_CAPABILITIES.includes(message.type)) return;
    const domain = message.type.slice(0, message.type.indexOf('.')) as BridgeDomain;
    handlersByDomain[domain](message, raw);
  }

  // message는 검사를 마친 envelope이고, raw는 받은 JSON 그대로다. 지도 모듈은 raw를 Swift에서 다시 읽는다.
  const handlersByDomain: Record<BridgeDomain, (message: BridgeMessage, raw: string) => void> = {
    map: (_message, raw) => handleMapMessage(raw),
    haptic: (message) => playHaptic(message),
    settings: () => {
      Linking.openSettings().catch(() => undefined);
    },
    auth: (message) => handleSignIn(message),
    notification: (message) => handleNotificationCommand(message),
  };

  // 로그인 창은 한 번에 하나만 연다. 결과는 웹 origin의 문서에만 넘긴다.
  function handleSignIn(message: BridgeMessage) {
    const request = signInRequestFrom(message.payload);
    if (request === null) return;
    if (signingIn.current) {
      replySignIn({ id: request.id, provider: request.provider, status: 'failed', reason: 'busy' });
      return;
    }
    signingIn.current = true;
    signInNatively(request)
      .then(replySignIn)
      .finally(() => {
        signingIn.current = false;
      });
  }

  function replySignIn(result: SignInResult) {
    sendToWeb({ type: SIGN_IN_RESULT, payload: { ...result } });
  }

  // 웹에 명령이 늘면 이 표에서 앱 tsc가 막는다.
  const notificationHandlers: Record<NotificationCommandType, (requestId: string) => void> = {
    'notification.requestPermission': (requestId) => replyRegistration(requestId, { ask: true }),
    'notification.getRegistration': (requestId) => replyRegistration(requestId, { ask: false }),
    'notification.takeOpened': (requestId) =>
      sendToWeb({ type: OPENED_EVENT, payload: { requestId, target: notificationTaps.take() } }),
  };

  function handleNotificationCommand(message: BridgeMessage) {
    const requestId = notificationRequestIdFrom(message.payload);
    if (requestId === null) return;
    notificationHandlers[message.type as NotificationCommandType](requestId);
  }

  function replyRegistration(requestId: string, { ask }: { ask: boolean }) {
    registrationFor(requestId, { ask }).then((registration) =>
      sendToWeb({ type: REGISTRATION_EVENT, payload: { ...registration } }),
    );
  }

  // 로그인 결과와 기기 토큰이 WebView가 연 로그인 제공자 화면에 가면 안 되어서 웹 origin 문서에만 넘긴다.
  function sendToWeb(message: BridgeMessage) {
    webViewRef.current?.injectJavaScript(bridgeEventScriptFor(message, WEB_ORIGIN));
  }

  const handleNotificationTap = useEffectEvent((tap: NotificationTap) => {
    if (notificationTaps.receive(tap, documentLoaded.current && webViewRef.current !== null) !== 'send') return;
    sendToWeb({ type: OPENED_EVENT, payload: { requestId: null, target: tap.target } });
  });

  useEffect(() => watchNotificationTaps(handleNotificationTap), []);

  function allowNavigation(request: ShouldStartLoadRequest) {
    const decision = navigationDecisionFor(request.url, request.isTopFrame, NAVIGABLE_ORIGINS);
    if (decision === 'openOutside') Linking.openURL(request.url).catch(() => undefined);
    return decision === 'load';
  }

  function handleMapMessage(raw: string) {
    const epoch = documentEpoch.current;
    // Imperative commands preserve ordering without a React render for every geometry update.
    commandQueue.current = commandQueue.current
      .catch(() => undefined)
      .then(async () => {
        if (epoch !== documentEpoch.current) return;
        await nativeMapRef.current?.handleMessage(raw);
      });
  }

  function forwardMapEvent(raw: string) {
    const message = bridgeMessageFrom(raw);
    if (message === null) {
      if (__DEV__) console.warn('지도 모듈이 보낸 이벤트의 envelope 형식이 맞지 않아 버립니다');
      return;
    }
    webViewRef.current?.injectJavaScript(bridgeEventScriptFor(message));
  }

  const webView = (
    <WebView
      ref={webViewRef}
      source={{ uri: WEB_URL }}
      style={[styles.webView, NativeRouteMap !== null && styles.transparentWebView]}
      containerStyle={NativeRouteMap !== null ? styles.transparentWebView : undefined}
      originWhitelist={EVERY_URL}
      onShouldStartLoadWithRequest={allowNavigation}
      allowsBackForwardNavigationGestures
      automaticallyAdjustContentInsets={NativeRouteMap !== null ? false : undefined}
      contentInsetAdjustmentBehavior={NativeRouteMap !== null ? 'never' : undefined}
      bounces={NativeRouteMap !== null ? false : undefined}
      injectedJavaScriptBeforeContentLoaded={APP_BOOTSTRAP}
      injectedJavaScriptBeforeContentLoadedForMainFrameOnly
      onMessage={handleMessage}
      onLoadStart={handleLoadStart}
      onLoad={handleLoad}
      onLoadEnd={SplashScreen.hide}
      onError={showLoadFailed}
      onHttpError={showLoadFailed}
      onContentProcessDidTerminate={reloadWebView}
    />
  );

  return (
    <>
      {loadFailed ? (
        <LoadFailedView onRetry={retryLoad} />
      ) : (
        NativeRouteMap !== null ? (
          <NativeRouteMap
            ref={nativeMapRef}
            style={styles.host}
            onMapEvent={(event) => forwardMapEvent(event.nativeEvent.message)}
          >
            {webView}
          </NativeRouteMap>
        ) : webView
      )}
      <StatusBar style="auto" />
    </>
  );
}

const styles = StyleSheet.create({
  host: { flex: 1 },
  transparentWebView: { backgroundColor: 'transparent' },
  webView: {
    flex: 1,
    backgroundColor: SPLASH_BACKGROUND_COLOR,
  },
});
