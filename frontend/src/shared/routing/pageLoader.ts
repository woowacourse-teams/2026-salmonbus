export type PageLoadSnapshot<T> =
  | { status: "idle" }
  | { status: "pending"; promise: Promise<T> }
  | { status: "ready"; promise: Promise<T>; value: T }
  | { status: "failed"; promise: Promise<T>; error: unknown };

export type PageLoadStatus = PageLoadSnapshot<unknown>["status"];

export interface PageLoader<T> {
  snapshot: () => PageLoadSnapshot<T>;
  load: () => Promise<T>;
  reset: () => void;
}

export function createPageLoader<T>(load: () => Promise<T>): PageLoader<T> {
  let state: PageLoadSnapshot<T> = { status: "idle" };

  function start(): Promise<T> {
    const promise = load();
    state = { status: "pending", promise };
    promise.then(
      (value) => {
        if (state.status === "pending" && state.promise === promise) state = { status: "ready", promise, value };
      },
      (error: unknown) => {
        if (state.status === "pending" && state.promise === promise) state = { status: "failed", promise, error };
      },
    );
    return promise;
  }

  return {
    snapshot: () => state,
    load: () => (state.status === "idle" ? start() : state.promise),
    reset: () => {
      if (state.status === "failed") state = { status: "idle" };
    },
  };
}
