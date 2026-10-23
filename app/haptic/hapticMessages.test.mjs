import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { HAPTIC_CAPABILITIES, hapticImpactStyleFrom } from './hapticMessages.ts';

describe('진동 메시지', () => {
  it('웹의 HapticCommands와 같은 type을 받는다', () => {
    assert.deepEqual(HAPTIC_CAPABILITIES, ['haptic.impact']);
  });

  it('light와 medium만 진동 세기로 읽는다', () => {
    assert.equal(hapticImpactStyleFrom({ style: 'light' }), 'light');
    assert.equal(hapticImpactStyleFrom({ style: 'medium' }), 'medium');
    assert.equal(hapticImpactStyleFrom({ style: 'heavy' }), null);
    assert.equal(hapticImpactStyleFrom({}), null);
  });
});
