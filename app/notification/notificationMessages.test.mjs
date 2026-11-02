import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  NOTIFICATION_CAPABILITIES,
  apnsEnvironmentFrom,
  notificationRequestIdFrom,
  notificationTargetFrom,
} from './notificationMessages.ts';
import { createTapInbox } from './tapInbox.ts';

const TARGET = { routeId: '234000884', stopId: '203000165', direction: 'UP' };

describe('알림 메시지', () => {
  it('웹의 NotificationCommands와 같은 type을 받는다', () => {
    assert.deepEqual(NOTIFICATION_CAPABILITIES, [
      'notification.requestPermission',
      'notification.getRegistration',
      'notification.takeOpened',
    ]);
  });

  it('requestId가 맞으면 읽는다', () => {
    assert.equal(notificationRequestIdFrom({ requestId: 'b3f1c2d4-0000-4000-8000-000000000001' }), 'b3f1c2d4-0000-4000-8000-000000000001');
  });

  for (const [name, payload] of [
    ['requestId 없음', {}],
    ['빈 requestId', { requestId: '' }],
    ['공백', { requestId: 'a b' }],
    ['65자', { requestId: 'a'.repeat(65) }],
    ['따옴표', { requestId: 'a"' }],
    ['숫자', { requestId: 1 }],
  ]) {
    it(`${name}이면 requestId로 읽지 않는다`, () => {
      assert.equal(notificationRequestIdFrom(payload), null);
    });
  }
});

describe('알림 대상', () => {
  it('payload의 target이 맞으면 대상으로 읽는다', () => {
    assert.deepEqual(notificationTargetFrom(TARGET), TARGET);
    assert.deepEqual(notificationTargetFrom({ ...TARGET, direction: 'DOWN', extra: 1 }), { ...TARGET, direction: 'DOWN' });
  });

  for (const [name, value] of [
    ['소문자 방향', { ...TARGET, direction: 'up' }],
    ['모르는 방향', { ...TARGET, direction: 'LEFT' }],
    ['숫자 노선', { ...TARGET, routeId: 234000884 }],
    ['빈 정류장', { ...TARGET, stopId: '' }],
    ['경로 문자가 든 노선', { ...TARGET, routeId: '../234' }],
    ['배열', [TARGET]],
    ['target 없음', undefined],
    ['null', null],
  ]) {
    it(`${name}이면 대상으로 읽지 않는다`, () => {
      assert.equal(notificationTargetFrom(value), null);
    });
  }
});

describe('APNs 환경', () => {
  it('서명 프로파일의 aps-environment를 먼저 따른다', () => {
    assert.equal(apnsEnvironmentFrom('development', 'other'), 'SANDBOX');
    assert.equal(apnsEnvironmentFrom('production', 'other'), 'PRODUCTION');
  });

  it('프로파일이 없으면 TestFlight와 App Store는 PRODUCTION, 시뮬레이터는 SANDBOX다', () => {
    assert.equal(apnsEnvironmentFrom(null, 'appStore'), 'PRODUCTION');
    assert.equal(apnsEnvironmentFrom(null, 'simulator'), 'SANDBOX');
  });

  it('정할 수 없으면 null이다', () => {
    assert.equal(apnsEnvironmentFrom(null, 'other'), null);
    assert.equal(apnsEnvironmentFrom('staging', 'appStore'), null);
  });
});

describe('알림 탭 칸', () => {
  it('웹 문서가 뜨는 중에 누른 탭은 칸에 두고 takeOpened에 한 번만 돌려준다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.accept({ identifier: 'n1', target: TARGET }, false), false);
    assert.deepEqual(inbox.take(), TARGET);
    assert.equal(inbox.take(), null);
  });

  it('웹 문서가 다 뜬 뒤 누른 탭은 바로 보내고 칸에 남기지 않는다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.accept({ identifier: 'n1', target: TARGET }, true), true);
    assert.equal(inbox.take(), null);
  });

  it('같은 탭이 두 길로 와도 한 번만 받는다', () => {
    const inbox = createTapInbox();
    assert.equal(inbox.accept({ identifier: 'n1', target: TARGET }, true), true);
    assert.equal(inbox.accept({ identifier: 'n1', target: TARGET }, true), false);
  });

  it('칸에는 마지막 탭만 남고, 바로 보낸 탭은 앞서 쌓인 탭을 지운다', () => {
    const inbox = createTapInbox();
    const later = { ...TARGET, routeId: '234000026' };
    inbox.accept({ identifier: 'n1', target: TARGET }, false);
    inbox.accept({ identifier: 'n2', target: later }, false);
    assert.deepEqual(inbox.take(), later);

    inbox.accept({ identifier: 'n3', target: TARGET }, false);
    assert.equal(inbox.accept({ identifier: 'n4', target: later }, true), true);
    assert.equal(inbox.take(), null);
  });
});
