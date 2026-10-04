import { REQUEST_DISPOSED, REQUEST_SUPERSEDED } from "./cancellation";

export interface RequestTicket {
  readonly signal: AbortSignal;
  isLatest(): boolean;
  abort(reason?: unknown): void;
}

export interface LatestRequestGate {
  issue(): RequestTicket;
  close(): void;
}

export function createLatestRequestGate(): LatestRequestGate {
  let latest: AbortController | null = null;

  return {
    issue() {
      latest?.abort(REQUEST_SUPERSEDED);
      const controller = new AbortController();
      latest = controller;

      return {
        signal: controller.signal,
        isLatest: () => latest === controller,
        abort: (reason) => controller.abort(reason),
      };
    },
    close() {
      latest?.abort(REQUEST_DISPOSED);
      latest = null;
    },
  };
}
