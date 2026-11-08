export interface DocumentLoadState {
  ready: boolean;
  failed: boolean;
}

export function initialDocumentLoad(): DocumentLoadState {
  return { ready: false, failed: false };
}

export function noteWebReady(state: DocumentLoadState): DocumentLoadState {
  return state.failed ? state : { ...state, ready: true };
}

export function noteLoadFailure(): DocumentLoadState {
  return { ready: false, failed: true };
}
