import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { createTapInbox } from './tapInbox.ts';

const TARGET = { routeId: '234000884', stopId: '203000165', direction: 'UP' };
const LATER = { ...TARGET, routeId: '234000026' };

describe('알림 탭 칸', () => {
  it('웹 문서가 뜨는 중에 누른 탭은 칸에 두고 takeOpened에 한 번만 돌려준다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.receive({ key: 'n1@1', target: TARGET }, false), 'held');
    assert.deepEqual(inbox.take(), TARGET);
    assert.equal(inbox.take(), null);
  });

  it('웹 문서가 다 뜬 뒤 누른 탭은 바로 보내고 칸에 남기지 않는다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.receive({ key: 'n1@1', target: TARGET }, true), 'send');
    assert.equal(inbox.take(), null);
  });

  it('같은 탭이 두 길로 와도 한 번만 받는다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.receive({ key: 'n1@1', target: TARGET }, true), 'send');
    assert.equal(inbox.receive({ key: 'n1@1', target: TARGET }, true), 'duplicate');
  });

  it('identifier가 같아도 받은 시각이 다른 알림은 새 탭이다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.receive({ key: 'collapse-1@1000', target: TARGET }, true), 'send');
    assert.equal(inbox.receive({ key: 'collapse-1@2000', target: TARGET }, true), 'send');
  });

  it('칸에는 마지막 탭만 남고, 바로 보낸 탭은 앞서 쌓인 탭을 지운다', () => {
    const inbox = createTapInbox();
    inbox.receive({ key: 'n1@1', target: TARGET }, false);
    inbox.receive({ key: 'n2@2', target: LATER }, false);
    assert.deepEqual(inbox.take(), LATER);

    inbox.receive({ key: 'n3@3', target: TARGET }, false);
    assert.equal(inbox.receive({ key: 'n4@4', target: LATER }, true), 'send');
    assert.equal(inbox.take(), null);
  });
});
