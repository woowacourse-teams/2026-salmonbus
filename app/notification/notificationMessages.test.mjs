import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  NOTIFICATION_CAPABILITIES,
  apnsEnvironmentFrom,
  notificationRequestIdFrom,
  openedTargetFrom,
} from './notificationMessages.ts';

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
  it('payload의 target 객체를 고치지 않고 넘긴다', () => {
    const withLaterField = { ...TARGET, platform: 'ios' };
    assert.equal(openedTargetFrom(withLaterField), withLaterField);
  });

  for (const [name, value] of [
    ['target 없음', undefined],
    ['null', null],
    ['문자열', '234000884'],
    ['배열', [TARGET]],
  ]) {
    it(`${name}이면 대상 없이 넘긴다`, () => {
      assert.equal(openedTargetFrom(value), null);
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
