import { login, logout } from '@react-native-seoul/kakao-login';
import { isLoginCancelled, type NativeLoginResult } from './loginBridge';

export async function beginKakaoLogin(): Promise<NativeLoginResult> {
  if (!process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY?.trim()) return 'unavailable';
  try {
    await login();
    // shortcut: 제공자 인증까지만 완료한다, 서버 교환 계약 확정 후 WebView 회원 인증을 연결한다.
    return 'providerAuthenticated';
  } catch (error) {
    return isLoginCancelled(error) ? 'cancelled' : 'failed';
  } finally {
    // 아직 사용하지 않는 제공자 토큰을 기기에 남기지 않는다.
    await logout().catch(() => undefined);
  }
}
