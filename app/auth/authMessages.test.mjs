import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { AUTH_CAPABILITIES, isCancellation, signInRequestFrom } from './authMessages.ts';

const NONCE = 'q8Kf2-Lm9_xYz.0123~abcd';

describe('로그인 메시지', () => {
  it('웹의 AuthCommands와 같은 type을 받는다', () => {
    assert.deepEqual(AUTH_CAPABILITIES, ['auth.signIn']);
  });

  it('id, 제공자, nonce가 맞으면 요청으로 읽는다', () => {
    assert.deepEqual(signInRequestFrom({ id: 'b3f1c2d4-0000-4000-8000-000000000001', provider: 'apple', nonce: NONCE }), {
      id: 'b3f1c2d4-0000-4000-8000-000000000001',
      provider: 'apple',
      nonce: NONCE,
    });
    assert.equal(signInRequestFrom({ id: 'a', provider: 'kakao', nonce: NONCE })?.provider, 'kakao');
  });

  for (const [name, payload] of [
    ['모르는 제공자', { id: 'a', provider: 'google', nonce: NONCE }],
    ['대문자 제공자', { id: 'a', provider: 'APPLE', nonce: NONCE }],
    ['빈 id', { id: '', provider: 'apple', nonce: NONCE }],
    ['id에 공백', { id: 'a b', provider: 'apple', nonce: NONCE }],
    ['짧은 nonce', { id: 'a', provider: 'apple', nonce: 'short' }],
    ['nonce에 따옴표', { id: 'a', provider: 'apple', nonce: `${NONCE}"` }],
    ['nonce 없음', { id: 'a', provider: 'apple' }],
  ]) {
    it(`${name}이면 요청으로 읽지 않는다`, () => {
      assert.equal(signInRequestFrom(payload), null);
    });
  }

  it('Apple과 카카오의 취소 코드만 취소로 본다', () => {
    assert.equal(isCancellation({ code: 'ERR_REQUEST_CANCELED' }), true);
    assert.equal(isCancellation({ code: 'Cancelled' }), true);
    assert.equal(isCancellation({ code: 'AccessDenied' }), true);
    assert.equal(isCancellation({ code: 'ERR_REQUEST_UNKNOWN' }), false);
    assert.equal(isCancellation(new Error('network')), false);
    assert.equal(isCancellation(null), false);
  });
});
