import * as AppleAuthentication from 'expo-apple-authentication';
import * as Crypto from 'expo-crypto';
import { initializeKakaoSDK } from '@react-native-kakao/core';
import { login as loginWithKakao } from '@react-native-kakao/user';
import { isCancellation, type SignInRequest, type SignInResult } from './authMessages';

const KAKAO_NATIVE_APP_KEY = process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY;

interface Credential {
  idToken: string;
  authorizationCode?: string;
}

let kakaoInitialization: Promise<void> | null = null;

/** 실패해도 reject하지 않고 결과로 돌려준다. 웹이 답을 못 받으면 로그인 버튼이 꺼진 채 남는다. */
export async function signInNatively({ id, provider, nonce }: SignInRequest): Promise<SignInResult> {
  try {
    const credential = provider === 'apple' ? await appleCredentialFor(nonce) : await kakaoCredentialFor(nonce);
    if (credential === null) return { id, provider, status: 'failed', reason: 'unavailable' };
    return { id, provider, status: 'success', ...credential };
  } catch (error) {
    if (isCancellation(error)) return { id, provider, status: 'cancelled' };
    if (__DEV__) console.warn(`${provider} 네이티브 로그인에 실패했습니다`, error);
    return { id, provider, status: 'failed', reason: 'error' };
  }
}

// Apple은 받은 nonce를 그대로 ID 토큰에 넣는다. 서버가 웹이 받은 nonce의 SHA-256(hex)과 비교한다.
async function appleCredentialFor(nonce: string): Promise<Credential | null> {
  if (!(await AppleAuthentication.isAvailableAsync())) return null;
  const hashedNonce = await Crypto.digestStringAsync(Crypto.CryptoDigestAlgorithm.SHA256, nonce);
  const { identityToken, authorizationCode } = await AppleAuthentication.signInAsync({
    requestedScopes: [],
    nonce: hashedNonce,
  });
  if (identityToken === null || authorizationCode === null) {
    throw new Error('Apple이 ID 토큰이나 인가 코드를 주지 않았습니다');
  }
  return { idToken: identityToken, authorizationCode };
}

// 카카오톡이 있으면 카카오톡 앱으로, 없거나 카카오톡 로그인이 안 되면 SDK가 카카오 계정 로그인으로 바꾼다.
async function kakaoCredentialFor(nonce: string): Promise<Credential | null> {
  if (!KAKAO_NATIVE_APP_KEY) return null;
  // 초기화는 이 빌드에 카카오 네이티브 모듈이 없을 때만 실패한다. 다시 시도해도 같으니 WebView 로그인으로 보낸다.
  try {
    kakaoInitialization ??= initializeKakaoSDK(KAKAO_NATIVE_APP_KEY);
    await kakaoInitialization;
  } catch {
    return null;
  }
  const token = await loginWithKakao({ nonce });
  if (!token.idToken) {
    throw new Error('카카오가 ID 토큰을 주지 않았습니다. 카카오 앱의 OpenID Connect 설정을 확인해 주세요');
  }
  return { idToken: token.idToken };
}
