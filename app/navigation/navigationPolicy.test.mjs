import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { LOGIN_PROVIDER_ORIGINS, navigationDecisionFor } from './navigationPolicy.ts';

const IN_APP_ORIGINS = ['https://dev.salmonbus.com', ...LOGIN_PROVIDER_ORIGINS];

describe('WebView 이동 판단', () => {
  it('웹과 로그인 제공자 화면은 앱 안에서 연다', () => {
    for (const url of [
      'https://dev.salmonbus.com/routes/234000016',
      'https://dev.salmonbus.com/api/auth/kakao/start?returnTo=%2F',
      'https://kauth.kakao.com/oauth/authorize?client_id=x',
      'https://accounts.kakao.com/login',
      'https://appleid.apple.com/auth/authorize',
    ]) {
      assert.equal(navigationDecisionFor(url, true, IN_APP_ORIGINS), 'load', url);
    }
  });

  it('origin 앞부분만 같은 주소는 바깥으로 넘긴다', () => {
    assert.equal(navigationDecisionFor('https://accounts.kakao.com.example.net/login', true, IN_APP_ORIGINS), 'openOutside');
    assert.equal(navigationDecisionFor('https://dev.salmonbus.com.example.net/', true, IN_APP_ORIGINS), 'openOutside');
    assert.equal(navigationDecisionFor('http://dev.salmonbus.com/', true, IN_APP_ORIGINS), 'openOutside');
  });

  it('목록 밖 주소와 다른 앱 스킴은 바깥으로 넘긴다', () => {
    assert.equal(navigationDecisionFor('https://www.notion.so/privacy', true, IN_APP_ORIGINS), 'openOutside');
    assert.equal(navigationDecisionFor('kakaotalk://login', true, IN_APP_ORIGINS), 'openOutside');
    assert.equal(navigationDecisionFor('mailto:salmonbus0731@gmail.com', true, IN_APP_ORIGINS), 'openOutside');
  });

  it('빈 프레임과 https 하위 프레임은 연다', () => {
    assert.equal(navigationDecisionFor('about:blank', false, IN_APP_ORIGINS), 'load');
    assert.equal(navigationDecisionFor('https://dkaptcha.kakao.com/dkaptcha/quiz', false, IN_APP_ORIGINS), 'load');
  });

  it('https가 아닌 하위 프레임은 열지도 넘기지도 않는다', () => {
    assert.equal(navigationDecisionFor('kakaokompassauth://authorize', false, IN_APP_ORIGINS), 'ignore');
    assert.equal(navigationDecisionFor('http://example.com/', false, IN_APP_ORIGINS), 'ignore');
  });

  it('주소로 읽을 수 없으면 열지 않는다', () => {
    assert.equal(navigationDecisionFor('not a url', true, IN_APP_ORIGINS), 'ignore');
  });
});
