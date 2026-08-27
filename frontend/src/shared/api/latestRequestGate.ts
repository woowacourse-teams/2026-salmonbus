export interface RequestTicket {
  readonly signal: AbortSignal;
  isLatest(): boolean;
  abort(): void;
}

export interface LatestRequestGate {
  issue(): RequestTicket;
  close(): void;
}

export function createLatestRequestGate(): LatestRequestGate {
  let latest: AbortController | null = null;

  return {
    issue() {
      latest?.abort();
      const controller = new AbortController();
      latest = controller;

      return {
        signal: controller.signal,
        isLatest: () => latest === controller,
        abort: () => controller.abort(),
      };
    },
    close() {
      latest?.abort();
      latest = null;
    },
  };
}
