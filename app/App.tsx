import { useEffect, useEffectEvent, useRef, useState } from 'react';
import * as Application from 'expo-application';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { Linking, StyleSheet } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import type { ShouldStartLoadRequest } from 'react-native-webview/lib/WebViewTypes';
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
import {
  initialDocumentLoad,
  noteLoadFailure,
  noteWebReady,
  WEB_READY_TIMEOUT_MS,
} from './notification/documentLoad';
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
const EVERY_URL = ['*'];
const SPLASH_BACKGROUND_COLOR = '#E8533F';
type DomainCapabilities<T> = { [K in keyof T]: readonly `${K & string}.${string}`[] };
function domainCapabilitiesOf<T extends DomainCapabilities<T>>(capabilities: T): T {
  return capabilities;
}

const CAPABILITIES_BY_DOMAIN = domainCapabilitiesOf({
  map: NativeRouteMap !== null ? [...NATIVE_MAP_CAPABILITIES, 'map.selectedStopOnly'] : [],
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
  const documentLoad = useRef(initialDocumentLoad());
  const webReadyWait = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);

  const showLoadFailed = () => {
    stopWaitingForWeb();
    documentLoad.current = noteLoadFailure();
    resetNativeMap();
    setLoadFailed(true);
    SplashScreen.hide();
  };
  const retryLoad = () => setLoadFailed(false);
  const reloadWebView = () => {
    handleLoadStart();
    webViewRef.current?.reload();
  };

  function handleLoadStart() {
    stopWaitingForWeb();
    documentLoad.current = initialDocumentLoad();
    resetNativeMap();
  }

  function handleLoadEnd(event: { nativeEvent: { url: string } }) {
    SplashScreen.hide();
    if (!isInAppUrl(event.nativeEvent.url) || documentLoad.current.ready || documentLoad.current.failed) return;
    if (!notificationTaps.holding()) return;
    stopWaitingForWeb();
    const epoch = documentEpoch.current;
    webReadyWait.current = setTimeout(() => {
      webReadyWait.current = null;
      if (documentEpoch.current === epoch && !documentLoad.current.ready && notificationTaps.holding()) showLoadFailed();
    }, WEB_READY_TIMEOUT_MS);
  }

  function stopWaitingForWeb() {
    if (webReadyWait.current === null) return;
    clearTimeout(webReadyWait.current);
    webReadyWait.current = null;
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

  const handlersByDomain: Record<BridgeDomain, (message: BridgeMessage, raw: string) => void> = {
    map: (_message, raw) => handleMapMessage(raw),
    haptic: (message) => playHaptic(message),
    settings: () => {
      Linking.openSettings().catch(() => undefined);
    },
    auth: (message) => handleSignIn(message),
    notification: (message) => handleNotificationCommand(message),
  };

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

  const notificationHandlers: Record<NotificationCommandType, (requestId: string) => void> = {
    'notification.requestPermission': (requestId) => replyRegistration(requestId, { ask: true }),
    'notification.getRegistration': (requestId) => replyRegistration(requestId, { ask: false }),
    'notification.takeOpened': (requestId) => {
      documentLoad.current = noteWebReady(documentLoad.current);
      if (documentLoad.current.ready) stopWaitingForWeb();
      const tap = documentLoad.current.ready ? notificationTaps.take() : null;
      sendOpened(requestId, tap?.target ?? null);
    },
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

  function sendOpened(requestId: string | null, target: Record<string, unknown> | null) {
    sendToWeb({ type: OPENED_EVENT, payload: { requestId, target } });
  }

  function sendToWeb(message: BridgeMessage) {
    webViewRef.current?.injectJavaScript(bridgeEventScriptFor(message, WEB_ORIGIN));
  }

  const handleNotificationTap = useEffectEvent((tap: NotificationTap) => {
    if (notificationTaps.receive(tap, documentLoad.current.ready && webViewRef.current !== null) !== 'send') return;
    sendOpened(null, tap.target);
  });

  useEffect(() => watchNotificationTaps(handleNotificationTap), []);

  function allowNavigation(request: ShouldStartLoadRequest) {
    const decision = navigationDecisionFor(request.url, request.isTopFrame, NAVIGABLE_ORIGINS);
    if (decision === 'openOutside') Linking.openURL(request.url).catch(() => undefined);
    return decision === 'load';
  }

  function handleMapMessage(raw: string) {
    const epoch = documentEpoch.current;
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
      onLoadEnd={handleLoadEnd}
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
