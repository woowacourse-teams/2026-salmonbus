export type NativeLoginResult = 'cancelled' | 'failed' | 'unavailable' | 'providerAuthenticated';

export function isLoginRequest(data: string, sourceUrl: string, webUrl: string): boolean {
  if (data.length > 512) return false;
  try {
    const source = new URL(sourceUrl);
    const expected = new URL(webUrl);
    if (source.origin !== expected.origin || source.pathname !== '/login') return false;
    const message: unknown = JSON.parse(data);
    return typeof message === 'object' && message !== null &&
      'type' in message && message.type === 'auth.login' &&
      'provider' in message && message.provider === 'KAKAO';
  } catch {
    return false;
  }
}

export function isLoginCancelled(error: unknown): boolean {
  return error instanceof Error && /ClientFailed\(Cancelled\)|user_cancelled_authorize/.test(error.message);
}

export function loginResultScript(status: NativeLoginResult): string {
  return `window.dispatchEvent(new CustomEvent('salmonbus:auth', {detail: ${JSON.stringify({ status })}})); true;`;
}
