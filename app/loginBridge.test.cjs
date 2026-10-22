const { test } = require('node:test');
const assert = require('node:assert/strict');
const { isLoginRequest, isLoginCancelled, loginResultScript } = require('./loginBridge.ts');

const request = JSON.stringify({ type: 'auth.login', provider: 'KAKAO' });
const web = 'https://www.salmonbus.com';

test('로그인 페이지의 카카오 요청만 받는다', () => {
  assert.equal(isLoginRequest(request, web + '/login?returnTo=%2F', web), true);
  for (const source of ['https://evil.test/login', web + '/mypage', 'invalid']) {
    assert.equal(isLoginRequest(request, source, web), false);
  }
  for (const data of ['invalid', '{}', JSON.stringify({ type: 'auth.login', provider: 'APPLE' }), 'x'.repeat(513)]) {
    assert.equal(isLoginRequest(data, web + '/login', web), false);
  }
});
test('사용자 취소와 인증 실패를 구분한다', () => {
  assert.equal(isLoginCancelled(new Error('ClientFailed(Cancelled): cancelled')), true);
  assert.equal(isLoginCancelled(new Error('AuthFailed(AccessDenied): failure')), false);
  assert.equal(isLoginCancelled(null), false);
});
test('웹에는 제공자 토큰 없이 상태만 전달한다', () => {
  assert.match(loginResultScript('providerAuthenticated'), /providerAuthenticated/);
  assert.doesNotMatch(loginResultScript('providerAuthenticated'), /accessToken|refreshToken|idToken/);
});
