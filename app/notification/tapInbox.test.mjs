import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { createTapInbox } from './tapInbox.ts';

const TARGET = { routeId: '234000884', stopId: '203000165', direction: 'UP' };
const LATER = { ...TARGET, routeId: '234000026' };

describe('알림 탭 칸', () => {
  it('문서가 뜨는 중에 받은 탭은 칸에 두고 한 번만 꺼낼 수 있다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.receive({ key: 'n1@1', target: TARGET }, false), 'held');
    assert.deepEqual(inbox.take(), { key: 'n1@1', target: TARGET });
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
    assert.deepEqual(inbox.take(), { key: 'n2@2', target: LATER });

    inbox.receive({ key: 'n3@3', target: TARGET }, false);
    assert.equal(inbox.receive({ key: 'n4@4', target: LATER }, true), 'send');
    assert.equal(inbox.take(), null);
  });

  it('대상을 못 읽은 탭도 칸에 두고 한 번만 꺼낼 수 있다', () => {
    const inbox = createTapInbox();
    inbox.receive({ key: 'n1@1', target: null }, false);
    assert.deepEqual(inbox.take(), { key: 'n1@1', target: null });
    assert.equal(inbox.take(), null);
  });

  it('꺼낸 탭을 도로 넣으면 한 번 더 꺼낼 수 있다', () => {
    const inbox = createTapInbox();
    inbox.receive({ key: 'n1@1', target: TARGET }, false);
    const taken = inbox.take();
    assert.ok(taken);
    assert.equal(inbox.restore(taken), true);
    assert.deepEqual(inbox.take(), { key: 'n1@1', target: TARGET });
    assert.equal(inbox.take(), null);
  });

  it('칸에 더 새로운 탭이 있으면 예전 탭을 도로 넣지 않는다', () => {
    const inbox = createTapInbox();
    inbox.receive({ key: 'n1@1', target: TARGET }, false);
    const taken = inbox.take();
    assert.ok(taken);
    inbox.receive({ key: 'n2@2', target: LATER }, false);
    assert.equal(inbox.restore(taken), false);
    assert.deepEqual(inbox.take(), { key: 'n2@2', target: LATER });
  });

  it('바로 보낸 더 새로운 탭이 있으면 예전 탭을 도로 넣지 않는다', () => {
    const inbox = createTapInbox();
    inbox.receive({ key: 'n1@1', target: TARGET }, false);
    const taken = inbox.take();
    assert.ok(taken);
    assert.equal(inbox.receive({ key: 'n2@2', target: LATER }, true), 'send');
    assert.equal(inbox.restore(taken), false);
    assert.equal(inbox.take(), null);
  });
});
