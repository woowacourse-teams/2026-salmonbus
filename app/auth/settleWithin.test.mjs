import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { settleWithin } from './settleWithin.ts';

describe('정해진 시간 안에 끝내기', () => {
  it('시간 안에 끝나면 그 값을 돌려준다', async () => {
    assert.equal(await settleWithin(Promise.resolve('id-token'), 1_000), 'id-token');
  });

  it('시간 안에 실패하면 그 오류를 그대로 던진다', async () => {
    const cancelled = Object.assign(new Error('사용자가 닫음'), { code: 'Cancelled' });
    await assert.rejects(settleWithin(Promise.reject(cancelled), 1_000), cancelled);
  });

  it('끝나지 않으면 정한 시간 뒤에 실패한다', async () => {
    await assert.rejects(settleWithin(new Promise(() => {}), 20), /20ms 안에 끝나지 않았습니다/);
  });
});
