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

  it('웹이 준비된 뒤 누른 탭은 바로 보내고 칸에 남기지 않는다', () => {
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

  it('10분 직전에는 탭을 꺼낼 수 있다', () => {
    let currentTime = 1_000;
    const inbox = createTapInbox(() => currentTime);
    inbox.receive({ key: 'n1@1', target: TARGET }, false);
    currentTime += 10 * 60 * 1_000 - 1;
    assert.deepEqual(inbox.take(), { key: 'n1@1', target: TARGET });
  });

  it('10분이 된 탭은 버리고 같은 탭으로 보관 시간을 늘리지 않는다', () => {
    let currentTime = 1_000;
    const inbox = createTapInbox(() => currentTime);
    const tap = { key: 'n1@1', target: TARGET };
    inbox.receive(tap, false);
    currentTime += 9 * 60 * 1_000;
    assert.equal(inbox.receive(tap, false), 'duplicate');
    currentTime += 60 * 1_000;
    assert.equal(inbox.take(), null);
    assert.equal(inbox.receive(tap, true), 'duplicate');
  });

  it('몇 시간 뒤 준비된 웹에는 지난 탭을 주지 않는다', () => {
    let currentTime = 1_000;
    const inbox = createTapInbox(() => currentTime);
    inbox.receive({ key: 'n1@1', target: TARGET }, false);
    currentTime += 3 * 60 * 60 * 1_000;
    assert.equal(inbox.take(), null);
    assert.equal(inbox.take(), null);
  });

  it('새 탭의 보관 기간은 새로 받은 시각부터 센다', () => {
    let currentTime = 1_000;
    const inbox = createTapInbox(() => currentTime);
    inbox.receive({ key: 'n1@1', target: TARGET }, false);
    currentTime += 9 * 60 * 1_000;
    inbox.receive({ key: 'n2@2', target: LATER }, false);
    currentTime += 60 * 1_000;
    assert.deepEqual(inbox.take(), { key: 'n2@2', target: LATER });
  });
});
