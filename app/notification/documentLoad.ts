export const WEB_READY_TIMEOUT_MS = 10_000;

export interface DocumentLoadState {
  started: boolean;
  ready: boolean;
  failed: boolean;
}

export function initialDocumentLoad(): DocumentLoadState {
  return { started: false, ready: false, failed: false };
}

export function noteDocumentStarted(state: DocumentLoadState): DocumentLoadState {
  return state.failed ? state : { ...state, started: true };
}

// 새 문서가 시작되기 전에 온 준비 신호는 이전 문서가 보낸 것이다.
export function noteWebReady(state: DocumentLoadState): DocumentLoadState {
  return state.failed || !state.started ? state : { ...state, ready: true };
}

export function noteLoadFailure(): DocumentLoadState {
  return { started: false, ready: false, failed: true };
}
