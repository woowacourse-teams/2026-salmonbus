import { StatusBar } from 'expo-status-bar';
import { StyleSheet } from 'react-native';
import { WebView } from 'react-native-webview';

function readWebUrl() {
  const webUrl = process.env.EXPO_PUBLIC_WEB_URL;
  if (!webUrl) {
    throw new Error('EXPO_PUBLIC_WEB_URL 값이 없습니다. app/.env에 웹 주소를 넣어 주세요.');
  }
  return webUrl;
}

const WEB_URL = readWebUrl();

export default function App() {
  return (
    <>
      <WebView source={{ uri: WEB_URL }} style={styles.webView} />
      <StatusBar style="auto" />
    </>
  );
}

const styles = StyleSheet.create({
  webView: {
    flex: 1,
  },
});
