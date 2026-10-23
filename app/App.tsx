import { useRef, useState } from 'react';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet } from 'react-native';
import { WebView, type WebViewMessageEvent } from 'react-native-webview';
import { LoadFailedView } from './LoadFailedView';
import {
  NativeRouteMap, NATIVE_MAP_BOOTSTRAP, isNativeMapMessage, nativeMapEventScript,
  type NativeRouteMapRef,
} from './native-map/NativeRouteMap';

SplashScreen.preventAutoHideAsync();

function readWebUrl() {
  const webUrl = process.env.EXPO_PUBLIC_WEB_URL;
  if (!webUrl) {
    throw new Error('EXPO_PUBLIC_WEB_URL 값이 없습니다. app/.env에 웹 주소를 넣어 주세요.');
  }
  return webUrl;
}

const WEB_URL = readWebUrl();
const IN_APP_ORIGINS = [new URL(WEB_URL).origin];
const SPLASH_BACKGROUND_COLOR = '#E8533F';

export default function App() {
  const webViewRef = useRef<WebView>(null);
  const nativeMapRef = useRef<NativeRouteMapRef>(null);
  const commandQueue = useRef(Promise.resolve());
  const documentEpoch = useRef(0);
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

  function handleMapMessage(event: WebViewMessageEvent) {
    const message = event.nativeEvent.data;
    if (!isNativeMapMessage(message)) return;
    try {
      if (!IN_APP_ORIGINS.includes(new URL(event.nativeEvent.url).origin)) return;
    } catch { return; }
    const epoch = documentEpoch.current;
    // Imperative commands preserve ordering without a React render for every geometry update.
    commandQueue.current = commandQueue.current
      .catch(() => undefined)
      .then(async () => {
        if (epoch !== documentEpoch.current) return;
        await nativeMapRef.current?.handleMessage(message);
      });
  }

  const webView = (
    <WebView
      ref={webViewRef}
      source={{ uri: WEB_URL }}
      style={[styles.webView, NativeRouteMap !== null && styles.transparentWebView]}
      containerStyle={NativeRouteMap !== null ? styles.transparentWebView : undefined}
      originWhitelist={IN_APP_ORIGINS}
      allowsBackForwardNavigationGestures
      automaticallyAdjustContentInsets={NativeRouteMap !== null ? false : undefined}
      contentInsetAdjustmentBehavior={NativeRouteMap !== null ? 'never' : undefined}
      bounces={NativeRouteMap !== null ? false : undefined}
      injectedJavaScriptBeforeContentLoaded={NativeRouteMap !== null ? NATIVE_MAP_BOOTSTRAP : undefined}
      injectedJavaScriptBeforeContentLoadedForMainFrameOnly
      onMessage={NativeRouteMap !== null ? handleMapMessage : undefined}
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
            onMapEvent={(event) => webViewRef.current?.injectJavaScript(nativeMapEventScript(event.nativeEvent.message))}
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
