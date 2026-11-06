export interface DocumentLoadState {
  epoch: number;
  failed: boolean;
  provisionalEpoch: number | null;
}

export function initialDocumentLoad(): DocumentLoadState {
  return { epoch: 0, failed: false, provisionalEpoch: null };
}

export function noteLoadStart(state: DocumentLoadState): DocumentLoadState {
  return { epoch: state.epoch + 1, failed: false, provisionalEpoch: null };
}

export function noteDocumentLoad(
  state: DocumentLoadState,
  inApp: boolean,
): { state: DocumentLoadState; documentLoaded: boolean; take: boolean } {
  if (!inApp || state.failed) return { state, documentLoaded: false, take: false };
  return { state: { ...state, provisionalEpoch: state.epoch }, documentLoaded: true, take: true };
}

export function noteLoadFailure(state: DocumentLoadState): { state: DocumentLoadState; restore: boolean } {
  const restore = state.provisionalEpoch === state.epoch;
  return { state: { epoch: state.epoch, failed: true, provisionalEpoch: null }, restore };
}
