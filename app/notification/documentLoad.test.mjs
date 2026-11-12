import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { initialDocumentLoad, noteDocumentStarted, noteLoadFailure, noteWebReady } from './documentLoad.ts';

describe('문서의 알림 수신 준비', () => {
  it('문서를 열 때는 준비되지 않고 문서가 시작된 뒤 웹의 준비 신호를 받으면 준비된다', () => {
    const started = noteDocumentStarted(initialDocumentLoad());
    assert.equal(started.ready, false);
    assert.equal(noteWebReady(started).ready, true);
  });

  it('새 문서가 시작되기 전에 온 준비 신호는 이전 문서의 것이라 받지 않는다', () => {
    assert.equal(noteWebReady(initialDocumentLoad()).ready, false);
  });

  it('실패한 문서는 늦게 준비 신호를 받아도 준비되지 않는다', () => {
    const failure = noteLoadFailure();
    assert.equal(failure.ready, false);
    assert.equal(noteWebReady(failure).ready, false);
  });

  it('다시 로드한 문서는 실패 상태를 지우고 새 준비 신호를 받는다', () => {
    noteWebReady(noteLoadFailure());
    const retry = noteDocumentStarted(initialDocumentLoad());
    assert.equal(retry.ready, false);
    assert.equal(noteWebReady(retry).ready, true);
  });
});
