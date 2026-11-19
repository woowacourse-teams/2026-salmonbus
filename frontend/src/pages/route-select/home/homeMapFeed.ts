import { homeMapDataFrom, type HomeMapData } from "./homeMapData";

export const HOME_MAP_FEED_URL = "/assets/home-events.json";
const REQUEST_TIMEOUT_MS = 10_000;
const REUSE_MS = 300_000;
const RETRY_DELAYS_MS = [2_000, 8_000];
const EMPTY_DATA: HomeMapData = { stops: [], routePaths: [] };

export interface HomeMapFeedState {
  status: "idle" | "loading" | "ready" | "error";
  data: HomeMapData;
}

export interface HomeMapFeedDeps {
  fetch: (input: string, init: RequestInit) => Promise<Response>;
  now: () => number;
  setTimeout: (run: () => void, ms: number) => number;
  clearTimeout: (id: number) => void;
}

export interface HomeMapFeed {
  getSnapshot: () => HomeMapFeedState;
  subscribe: (listener: () => void) => () => void;
  subscribeNeeded: (listener: () => void) => () => void;
  prefetch: () => void;
}

export function createHomeMapFeed(deps: HomeMapFeedDeps): HomeMapFeed {
  const listeners = new Set<() => void>();
  let state: HomeMapFeedState = { status: "idle", data: EMPTY_DATA };
  let inflight: Promise<void> | null = null;
  let loadedAt: number | null = null;
  let lastText: string | null = null;
  let needCount = 0;
  let attempt = 0;
  let retryTimer: number | null = null;

  function setState(next: HomeMapFeedState) {
    if (next.status === state.status && next.data === state.data) return;
    state = next;
    listeners.forEach((listener) => listener());
  }

  function clearRetry() {
    if (retryTimer === null) return;
    deps.clearTimeout(retryTimer);
    retryTimer = null;
  }

  async function request() {
    const controller = new AbortController();
    const timeout = deps.setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);
    try {
      const response = await deps.fetch(HOME_MAP_FEED_URL, { signal: controller.signal, cache: "no-cache" });
      if (!response.ok) throw new Error("지도 데이터 조회 실패");
      const text = await response.text();
      const data = text === lastText ? state.data : homeMapDataFrom(JSON.parse(text));
      lastText = text;
      loadedAt = deps.now();
      attempt = 0;
      setState({ status: "ready", data });
    } catch {
      setState({ status: "error", data: state.data });
      scheduleRetry();
    } finally {
      deps.clearTimeout(timeout);
    }
  }

  function load() {
    clearRetry();
    if (inflight) return;
    setState({ status: state.status === "ready" ? "ready" : "loading", data: state.data });
    inflight = request().finally(() => {
      inflight = null;
    });
  }

  function scheduleRetry() {
    const delay = RETRY_DELAYS_MS[attempt];
    if (needCount === 0 || delay === undefined) return;
    attempt += 1;
    retryTimer = deps.setTimeout(() => {
      retryTimer = null;
      if (needCount > 0) load();
    }, delay);
  }

  function ensureFresh() {
    if (inflight) return;
    if (loadedAt !== null && deps.now() - loadedAt < REUSE_MS) return;
    attempt = 0;
    load();
  }

  function subscribe(listener: () => void) {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  }

  function subscribeNeeded(listener: () => void) {
    const unsubscribe = subscribe(listener);
    needCount += 1;
    ensureFresh();
    return () => {
      unsubscribe();
      needCount -= 1;
      if (needCount === 0) clearRetry();
    };
  }

  return { getSnapshot: () => state, subscribe, subscribeNeeded, prefetch: ensureFresh };
}

export const homeMapFeed = createHomeMapFeed({
  fetch: (input, init) => fetch(input, init),
  now: () => Date.now(),
  setTimeout: (run, ms) => window.setTimeout(run, ms),
  clearTimeout: (id) => window.clearTimeout(id),
});
