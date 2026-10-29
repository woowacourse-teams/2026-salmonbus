import { useRef, useState } from 'react';
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
import { SETTINGS_CAPABILITIES } from './settings/settingsMessages';

SplashScreen.preventAutoHideAsync();

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
});
type BridgeDomain = keyof typeof CAPABILITIES_BY_DOMAIN;
const APP_CAPABILITIES: readonly string[] = Object.values(CAPABILITIES_BY_DOMAIN).flat();
const APP_BOOTSTRAP = appBootstrapScriptFor({
  appVersion: Application.nativeApplicationVersion ?? 'unknown',
  capabilities: APP_CAPABILITIES,
});

export default function App() {
  const webViewRef = useRef<WebView>(null);
  const nativeMapRef = useRef<NativeRouteMapRef>(null);
  const commandQueue = useRef(Promise.resolve());
  const documentEpoch = useRef(0);
  const signingIn = useRef(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const showLoadFailed = () => {
    resetNativeMap();
    setLoadFailed(true);
    SplashScreen.hide();
  };
  const retryLoad = () => setLoadFailed(false);
  const reloadWebView = () => webViewRef.current?.reload();

  function resetNativeMap() {
    documentEpoch.current += 1;
    commandQueue.current = commandQueue.current
      .catch(() => undefined)
      .then(() => nativeMapRef.current?.reset());
  }

  function handleMessage(event: WebViewMessageEvent) {
    try {
      if (!IN_APP_ORIGINS.includes(new URL(event.nativeEvent.url).origin)) return;
    } catch { return; }
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
    webViewRef.current?.injectJavaScript(bridgeEventScriptFor({ type: SIGN_IN_RESULT, payload: { ...result } }, WEB_ORIGIN));
  }

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
      onLoadStart={resetNativeMap}
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
