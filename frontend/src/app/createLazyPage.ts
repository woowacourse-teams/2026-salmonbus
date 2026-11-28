import { createElement, use, type ComponentType } from "react";
import type { PageLoader, PageLoadStatus } from "@/shared/routing/pageLoader";
import type { LazyPageName } from "@/shared/routing/pagePreload";

export interface LazyPage {
  readonly name: LazyPageName;
  readonly Page: ComponentType;
  load: () => Promise<unknown>;
  preload: () => void;
  retry: () => void;
  status: () => PageLoadStatus;
}

export function createLazyPage<M>(
  name: LazyPageName,
  group: PageLoader<M>,
  pick: (module: M) => ComponentType,
): LazyPage {
  function Page() {
    const snapshot = group.snapshot();
    if (snapshot.status === "failed") throw snapshot.error;
    const loaded = snapshot.status === "ready" ? snapshot.value : use(group.load());
    return createElement(pick(loaded));
  }
  return {
    name,
    Page,
    load: group.load,
    preload: group.preload,
    retry: group.reset,
    status: () => group.snapshot().status,
  };
}
