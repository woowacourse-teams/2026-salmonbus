import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { WEB_READY_TIMEOUT_MS } from '../notification/documentLoad.ts';
import { CURRENT_STARTUP_POLICY as CURRENT, initialStartup, startupStep } from './startupReadiness.ts';
const TEST_SHELL = {
  splashHideOn: 'webReady',
  deadlines: [
    { name: 'document', from: 'loadStarted', until: 'documentLoaded', ms: 3, when: 'always' },
    { name: 'web', from: 'documentLoaded', until: 'webReady', ms: 5, when: 'always' },
    { name: 'map', from: 'mapMounted', until: 'mapReady', ms: 7, when: 'always' },
  ],
};

function run(policy, events, state = initialStartup()) {
  const effects = [];
  for (const event of events) {
    const step = startupStep(policy, state, event);
    state = step.state;
    effects.push(...step.effects);
  }
  return { state, effects };
}
const types = (effects) => effects.map((effect) => effect.type);
const start = [{ type: 'loadStarted' }, { type: 'documentStarted' }];

describe('현재 정책 재현', () => {
  it('일반 cold launch는 loadEnd에 스플래시를 내리고 타이머를 걸지 않는다', () => {
    const { state, effects } = run(CURRENT, [...start, { type: 'loadEnded', inApp: true, holdingTap: false }]);
    assert.equal(state.splashHidden, true);
    assert.deepEqual(state.timers, []);
    assert.equal(types(effects).filter((type) => type === 'hideSplash').length, 1);
    assert.equal(types(effects).includes('startTimer'), false);
  });

  it('보관한 탭이 있으면 loadEnd에서 타이머를 걸고 만료되면 실패 화면으로 간다', () => {
    const loaded = run(CURRENT, [...start, { type: 'loadEnded', inApp: true, holdingTap: true }]);
    assert.deepEqual(loaded.state.timers, ['webAlive']);
    const fired = run(CURRENT, [{ type: 'timerFired', name: 'webAlive', epoch: loaded.state.epoch, holdingTap: true }], loaded.state);
    assert.equal(fired.state.failed, true);
    assert.deepEqual(fired.state.timers, []);
    assert.deepEqual(types(fired.effects), ['resetNativeMap', 'showLoadFailed', 'hideSplash']);
  });

  it('준비 신호가 오면 타이머를 지운다', () => {
    const { state, effects } = run(CURRENT, [
      ...start,
      { type: 'loadEnded', inApp: true, holdingTap: true },
      { type: 'webAlive' },
    ]);
    assert.deepEqual(state.timers, []);
    assert.equal(types(effects).at(-1), 'cancelTimer');
  });

  it('앱 밖 문서의 loadEnd는 스플래시만 내린다', () => {
    const { state } = run(CURRENT, [...start, { type: 'loadEnded', inApp: false, holdingTap: true }]);
    assert.equal(state.splashHidden, true);
    assert.equal(state.loaded, false);
    assert.deepEqual(state.timers, []);
  });

  it('해시만 바뀐 loadEnd는 타이머를 다시 건다', () => {
    const { state, effects } = run(CURRENT, [
      ...start,
      { type: 'loadEnded', inApp: true, holdingTap: true },
      { type: 'loadEnded', inApp: true, holdingTap: true },
    ]);
    assert.deepEqual(state.timers, ['webAlive']);
    assert.deepEqual(types(effects).slice(-2), ['cancelTimer', 'startTimer']);
  });
});

function startHost(policy = CURRENT) {
  let state = initialStartup();
  let now = 0;
  let holding = false;
  let retryScreen = false;
  const timers = new Map();
  const apply = (event) => {
    const step = startupStep(policy, state, event);
    state = step.state;
    for (const effect of step.effects) {
      if (effect.type === 'startTimer') timers.set(effect.name, { epoch: effect.epoch, dueAt: now + effect.ms });
      if (effect.type === 'cancelTimer') timers.delete(effect.name);
      if (effect.type === 'showLoadFailed') retryScreen = true;
    }
  };
  return {
    start() {
      retryScreen = false;
      apply({ type: 'loadStarted' });
      apply({ type: 'documentStarted' });
    },
    tap: () => (holding = true),
    loadEnd: (inApp = true) => apply({ type: 'loadEnded', inApp, holdingTap: holding }),
    ready() {
      apply({ type: 'webAlive' });
      if (state.alive) holding = false;
    },
    advanceTime(ms) {
      now += ms;
      for (const [name, timer] of [...timers]) {
        if (timer.dueAt > now) continue;
        timers.delete(name);
        apply({ type: 'timerFired', name, epoch: timer.epoch, holdingTap: holding });
      }
    },
    retryScreenShown: () => retryScreen,
    state: () => state,
  };
}

describe('현재 정책: 준비 신호가 오지 않는 문서 (appNotification.test.mjs와 같은 결과)', () => {
  it('현재 정책의 대기 시간은 WEB_READY_TIMEOUT_MS다', () => {
    assert.deepEqual(CURRENT, {
      splashHideOn: 'loadEnded',
      deadlines: [{ name: 'webAlive', from: 'documentLoaded', until: 'webAlive', ms: WEB_READY_TIMEOUT_MS, when: 'holdingTap' }],
    });
  });

  it('보관한 탭이 있는데 문서가 다 뜬 뒤 10초 동안 준비 신호가 없으면 다시 시도 화면을 띄운다', () => {
    const app = startHost();
    app.start();
    app.tap();
    app.loadEnd();
    app.advanceTime(9_999);
    assert.equal(app.retryScreenShown(), false);
    app.advanceTime(1);
    assert.equal(app.retryScreenShown(), true);
  });

  it('다시 시도 화면에서 다시 읽은 문서가 준비되면 실패가 풀리고 준비 상태가 된다', () => {
    const app = startHost();
    app.start();
    app.tap();
    app.loadEnd();
    app.advanceTime(10_000);
    assert.equal(app.state().failed, true);
    app.start();
    app.ready();
    assert.equal(app.state().failed, false);
    assert.equal(app.state().alive, true);
    assert.deepEqual(app.state().timers, []);
  });

  it('준비 신호가 오면 다시 시도 화면을 띄우지 않는다', () => {
    const app = startHost();
    app.start();
    app.tap();
    app.loadEnd();
    app.ready();
    app.advanceTime(10_000);
    assert.equal(app.retryScreenShown(), false);
  });

  it('보관한 탭이 없거나 앱 밖 문서면 기다리지 않는다', () => {
    const app = startHost();
    app.start();
    app.loadEnd();
    app.tap();
    app.loadEnd(false);
    app.advanceTime(10_000);
    assert.equal(app.retryScreenShown(), false);
  });

  it('새 문서를 읽기 시작하면 이전 문서의 기다림을 멈춘다', () => {
    const app = startHost();
    app.start();
    app.tap();
    app.loadEnd();
    app.advanceTime(5_000);
    app.start();
    app.advanceTime(5_000);
    assert.equal(app.retryScreenShown(), false);
  });
});

describe('이전 문서 신호 차단', () => {
  it('새 문서 시작 전의 준비 신호는 받지 않는다', () => {
    const { state } = run(CURRENT, [...start, { type: 'loadStarted' }, { type: 'webAlive' }]);
    assert.equal(state.alive, false);
  });

  it('이전 epoch의 타이머 만료는 버린다', () => {
    const loaded = run(CURRENT, [...start, { type: 'loadEnded', inApp: true, holdingTap: true }]);
    const reloaded = run(CURRENT, [{ type: 'loadStarted' }], loaded.state);
    assert.deepEqual(types(reloaded.effects), ['cancelTimer', 'resetNativeMap']);
    const stale = run(CURRENT, [{ type: 'timerFired', name: 'webAlive', epoch: loaded.state.epoch, holdingTap: true }], reloaded.state);
    assert.equal(stale.state.failed, false);
    assert.deepEqual(stale.effects, []);
  });

  it('다른 documentId의 webReady는 버린다', () => {
    const { state } = run(TEST_SHELL, [
      { type: 'loadStarted' },
      { type: 'documentStarted', documentId: 'b' },
      { type: 'webReady', documentId: 'a' },
    ]);
    assert.equal(state.webReady, false);
    assert.equal(state.splashHidden, false);
  });

  it('이전 지도 세션의 ready는 버린다', () => {
    const { state } = run(TEST_SHELL, [
      ...start,
      { type: 'mapMounted', sessionId: 's1' },
      { type: 'loadStarted' },
      { type: 'documentStarted' },
      { type: 'mapReady', sessionId: 's1' },
      { type: 'mapMounted', sessionId: 's2' },
      { type: 'mapReady', sessionId: 's1' },
    ]);
    assert.equal(state.mapReady, false);
    assert.deepEqual(state.timers, ['document', 'map']);
  });
});

describe('스플래시와 실패 화면', () => {
  it('webReady 정책은 webReady나 실패 화면에서만 스플래시를 내린다', () => {
    const waiting = run(TEST_SHELL, [...start, { type: 'loadEnded', inApp: true, holdingTap: false }]);
    assert.equal(waiting.state.splashHidden, false);
    const ready = run(TEST_SHELL, [{ type: 'webReady' }], waiting.state);
    assert.equal(ready.state.splashHidden, true);
    assert.deepEqual(ready.state.timers, []);
    const expired = run(TEST_SHELL, [{ type: 'timerFired', name: 'web', epoch: waiting.state.epoch, holdingTap: false }], waiting.state);
    assert.deepEqual(types(expired.effects), ['resetNativeMap', 'showLoadFailed', 'hideSplash']);
  });
});

describe('무작위 순서 불변식', () => {
  it('타이머는 항상 현재 epoch에만 남고 실패 뒤에는 남지 않는다', () => {
    let seed = 442;
    const random = (limit) => {
      seed = (seed * 1_103_515_245 + 12_345) % 2_147_483_648;
      return seed % limit;
    };
    for (const policy of [CURRENT, TEST_SHELL]) {
      for (let round = 0; round < 400; round += 1) {
        let state = initialStartup();
        const host = new Map();
        let hides = 0;
        for (let index = 0; index < 60; index += 1) {
          const names = policy.deadlines.map(({ name }) => name);
          const pool = [
            { type: 'loadStarted' },
            { type: 'documentStarted', documentId: ['a', 'b'][random(2)] },
            { type: 'loadEnded', inApp: random(4) > 0, holdingTap: random(2) === 0 },
            { type: 'webAlive' },
            { type: 'webReady', documentId: [undefined, 'a', 'b'][random(3)] },
            { type: 'mapMounted', sessionId: ['s1', 's2'][random(2)] },
            { type: 'mapReady', sessionId: ['s1', 's2'][random(2)] },
            { type: 'loadFailed' },
            { type: 'timerFired', name: names[random(names.length)], epoch: state.epoch - random(2), holdingTap: random(2) === 0 },
          ];
          const event = pool[random(pool.length)];
          if (event.type === 'timerFired' && host.get(event.name) === event.epoch) host.delete(event.name);
          const step = startupStep(policy, state, event);
          for (const effect of step.effects) {
            if (effect.type === 'startTimer') {
              assert.equal(host.has(effect.name), false);
              host.set(effect.name, effect.epoch);
            }
            if (effect.type === 'cancelTimer') assert.equal(host.delete(effect.name), true);
            if (effect.type === 'hideSplash') hides += 1;
          }
          if (types(step.effects).includes('hideSplash')) {
            const failed = types(step.effects).includes('showLoadFailed');
            const stages = step.effects.filter((effect) => effect.type === 'stage').map((effect) => effect.stage);
            const released =
              (policy.splashHideOn === 'loadEnded' && event.type === 'loadEnded') || stages.includes(policy.splashHideOn);
            assert.ok(failed || released);
          }
          state = step.state;
          assert.deepEqual([...host.keys()].sort(), [...state.timers].sort());
          for (const epoch of host.values()) assert.equal(epoch, state.epoch);
          if (state.failed) assert.deepEqual(state.timers, []);
          if (types(step.effects).includes('showLoadFailed')) assert.equal(types(step.effects).includes('resetNativeMap'), true);
          if (state.mapReady) assert.notEqual(state.mapSessionId, null);
        }
        assert.ok(hides <= 60);
      }
    }
  });
});
