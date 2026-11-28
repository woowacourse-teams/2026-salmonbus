export type PageLoadSnapshot<T> =
  | { status: "idle" }
  | { status: "pending"; promise: Promise<T> }
  | { status: "ready"; promise: Promise<T>; value: T }
  | { status: "failed"; promise: Promise<T>; error: unknown };

export type PageLoadStatus = PageLoadSnapshot<unknown>["status"];

export interface PageLoader<T> {
  snapshot: () => PageLoadSnapshot<T>;
  load: () => Promise<T>;
  preload: () => void;
  reset: () => void;
}

type PendingState<T> = { status: "pending"; promise: Promise<T>; awaited: boolean };
type LoaderState<T> = Exclude<PageLoadSnapshot<T>, { status: "pending" }> | PendingState<T>;

export function createPageLoader<T>(load: () => Promise<T>): PageLoader<T> {
  let state: LoaderState<T> = { status: "idle" };

  function start(awaited: boolean): Promise<T> {
    const promise = load();
    const pending: PendingState<T> = { status: "pending", promise, awaited };
    state = pending;
    promise.then(
      (value) => {
        if (state === pending) state = { status: "ready", promise, value };
      },
      (error: unknown) => {
        if (state === pending) {
          state = pending.awaited ? { status: "failed", promise, error } : { status: "idle" };
        }
      },
    );
    return promise;
  }

  return {
    snapshot: () => (state.status === "pending" ? { status: "pending", promise: state.promise } : state),
    load: () => {
      if (state.status === "idle") return start(true);
      if (state.status === "pending") state.awaited = true;
      return state.promise;
    },
    preload: () => {
      if (state.status === "idle") start(false);
    },
    reset: () => {
      if (state.status === "failed") state = { status: "idle" };
    },
  };
}
