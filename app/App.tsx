import { useRef, useState } from 'react';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { Platform, StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import { LoadFailedView } from './LoadFailedView';
import { beginKakaoLogin } from './kakaoLogin';
import { isLoginRequest, loginResultScript } from './loginBridge';

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
  const [loadFailed, setLoadFailed] = useState(false);
  const loginPending = useRef(false);

  const handleLogin = async (data: string, sourceUrl: string) => {
    if (Platform.OS !== 'ios' || loginPending.current || !isLoginRequest(data, sourceUrl, WEB_URL)) return;
    loginPending.current = true;
    try {
      const result = await beginKakaoLogin();
      webViewRef.current?.injectJavaScript(loginResultScript(result));
    } finally {
      loginPending.current = false;
    }
  };

  const showLoadFailed = () => {
    setLoadFailed(true);
    SplashScreen.hide();
  };
  const retryLoad = () => setLoadFailed(false);
  const reloadWebView = () => webViewRef.current?.reload();

  return (
    <>
      {loadFailed ? (
        <LoadFailedView onRetry={retryLoad} />
      ) : (
        <WebView
          ref={webViewRef}
          source={{ uri: WEB_URL }}
          style={styles.webView}
          originWhitelist={IN_APP_ORIGINS}
          injectedJavaScriptBeforeContentLoaded={Platform.OS === 'ios' ? 'window.__SALMONBUS_NATIVE_AUTH__ = true; true;' : 'true;'}
          onMessage={(event) => { void handleLogin(event.nativeEvent.data, event.nativeEvent.url); }}
          allowsBackForwardNavigationGestures
          onLoadEnd={SplashScreen.hide}
          onError={showLoadFailed}
          onHttpError={showLoadFailed}
          onContentProcessDidTerminate={reloadWebView}
        />
      )}
      <StatusBar style="auto" />
    </>
  );
}

const styles = StyleSheet.create({
  webView: {
    flex: 1,
    backgroundColor: SPLASH_BACKGROUND_COLOR,
  },
});
