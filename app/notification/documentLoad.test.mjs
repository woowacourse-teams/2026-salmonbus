import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import {
  initialDocumentLoad,
  noteDocumentLoad,
  noteLoadFailure,
  noteLoadStart,
} from './documentLoad.ts';

describe('문서 로드와 알림 탭', () => {
  it('HTTP 실패가 로드보다 먼저면 꺼내지 않고 칸을 비우지 않는다', () => {
    const started = noteLoadStart(initialDocumentLoad());
    const failure = noteLoadFailure(started);
    const load = noteDocumentLoad(failure.state, true);

    assert.equal(failure.restore, false);
    assert.equal(load.take, false);
    assert.equal(load.documentLoaded, false);
  });

  it('로드 다음에 HTTP나 네트워크 실패가 오면 꺼낸 탭을 도로 넣는다', () => {
    const started = noteLoadStart(initialDocumentLoad());
    const load = noteDocumentLoad(started, true);
    const failure = noteLoadFailure(load.state);

    assert.equal(load.take, true);
    assert.equal(load.documentLoaded, true);
    assert.equal(failure.restore, true);
    assert.equal(failure.state.failed, true);
  });

  it('실패 알림이 두 번 오면 도로 넣기는 한 번이다', () => {
    const load = noteDocumentLoad(noteLoadStart(initialDocumentLoad()), true);
    const first = noteLoadFailure(load.state);
    const second = noteLoadFailure(first.state);

    assert.equal(first.restore, true);
    assert.equal(second.restore, false);
  });

  it('실패한 뒤 다시 시도한 로드는 꺼낸다', () => {
    const failed = noteLoadFailure(noteLoadStart(initialDocumentLoad())).state;
    const retry = noteDocumentLoad(noteLoadStart(failed), true);

    assert.equal(retry.take, true);
    assert.equal(retry.documentLoaded, true);
  });

  it('성공한 로드 다음 다른 문서의 실패는 도로 넣지 않는다', () => {
    const load = noteDocumentLoad(noteLoadStart(initialDocumentLoad()), true);
    const next = noteLoadStart(load.state);
    const failure = noteLoadFailure(next);

    assert.equal(failure.restore, false);
  });

  it('앱 밖 문서는 꺼내지 않는다', () => {
    const load = noteDocumentLoad(noteLoadStart(initialDocumentLoad()), false);

    assert.equal(load.take, false);
    assert.equal(load.documentLoaded, false);
  });
});
