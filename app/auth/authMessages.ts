// 웹의 frontend/src/shared/native-auth/nativeAuth.types.ts와 같은 메시지를 주고받는다. 웹의 bridge.test.ts가 목록을 비교한다.
export const AUTH_CAPABILITIES = ['auth.signIn'] as const;
export const SIGN_IN_RESULT = 'auth.signInResult';

export type NativeLoginProvider = 'apple' | 'kakao';

export interface SignInRequest {
  id: string;
  provider: NativeLoginProvider;
  nonce: string;
}

/** unavailable: 이 앱에서 그 제공자로 로그인할 수 없다(카카오 키 없음 등). 웹은 WebView 로그인으로 간다. */
export type SignInFailureReason = 'unavailable' | 'busy' | 'error';

export type SignInResult =
  | { id: string; provider: NativeLoginProvider; status: 'success'; idToken: string; authorizationCode?: string }
  | { id: string; provider: NativeLoginProvider; status: 'cancelled' }
  | { id: string; provider: NativeLoginProvider; status: 'failed'; reason: SignInFailureReason };

const REQUEST_ID = /^[A-Za-z0-9-]{1,64}$/;
const NONCE = /^[A-Za-z0-9._~-]{16,128}$/;
// expo-apple-authentication은 ERR_REQUEST_CANCELED, 카카오 SDK는 사용자가 닫으면 Cancelled, 동의하지 않으면 AccessDenied를 준다.
const CANCELLATION_CODES = ['ERR_REQUEST_CANCELED', 'Cancelled', 'AccessDenied'];

export function signInRequestFrom(payload: Record<string, unknown>): SignInRequest | null {
  const { id, provider, nonce } = payload;
  if (typeof id !== 'string' || !REQUEST_ID.test(id)) return null;
  if (provider !== 'apple' && provider !== 'kakao') return null;
  if (typeof nonce !== 'string' || !NONCE.test(nonce)) return null;
  return { id, provider, nonce };
}

export function isCancellation(error: unknown): boolean {
  if (error === null || typeof error !== 'object' || !('code' in error)) return false;
  return typeof error.code === 'string' && CANCELLATION_CODES.includes(error.code);
}
