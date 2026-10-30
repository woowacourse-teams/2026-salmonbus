// 웹 로그인이 WebView 안에서 끝나도록 제공자 로그인 화면을 앱 안에서 연다.
// 앱이 네이티브 로그인을 못 할 때(BE API 없음, 카카오 키 없음) 웹이 이 화면들로 이동한다.
export const LOGIN_PROVIDER_ORIGINS = [
  'https://kauth.kakao.com',
  'https://accounts.kakao.com',
  'https://appleid.apple.com',
] as const;

export type NavigationDecision = 'load' | 'openOutside' | 'ignore';

/**
 * origin을 정확히 비교한다. react-native-webview의 originWhitelist는 앞부분만 맞춰 보므로
 * https://accounts.kakao.com.example.net도 통과시킨다.
 * 하위 프레임은 https면 연다. 카카오 로그인 중 보안문자가 dkaptcha.kakao.com 프레임으로 뜨고,
 * 하위 프레임을 Safari로 넘기면 로그인 화면은 그대로인 채 Safari만 열린다.
 */
export function navigationDecisionFor(
  url: string,
  isTopFrame: boolean,
  inAppOrigins: readonly string[],
): NavigationDecision {
  if (url === 'about:blank' || url === 'about:srcdoc') return 'load';
  let target: URL;
  try {
    target = new URL(url);
  } catch {
    return 'ignore';
  }
  if (inAppOrigins.includes(target.origin)) return 'load';
  if (!isTopFrame) return target.protocol === 'https:' ? 'load' : 'ignore';
  return 'openOutside';
}
