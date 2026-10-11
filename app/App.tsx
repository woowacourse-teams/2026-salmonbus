import { useState } from 'react';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';
import { LoadFailedView } from './LoadFailedView';

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
  const [loadFailed, setLoadFailed] = useState(false);

  const showLoadFailed = () => setLoadFailed(true);
  const retryLoad = () => setLoadFailed(false);

  return (
    <>
      {loadFailed ? (
        <LoadFailedView onRetry={retryLoad} />
      ) : (
        <WebView
          source={{ uri: WEB_URL }}
          style={styles.webView}
          originWhitelist={IN_APP_ORIGINS}
          allowsBackForwardNavigationGestures
          onLoadEnd={SplashScreen.hide}
          onError={showLoadFailed}
          onHttpError={showLoadFailed}
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
