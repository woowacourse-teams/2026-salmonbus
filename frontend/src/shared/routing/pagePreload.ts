import { matchPath } from "react-router";

export type LazyPageName = "favorites" | "mypage" | "login" | "nickname" | "whats-new";

export interface PagePreloadEntry {
  name: LazyPageName;
  paths: readonly string[];
  preload: () => void;
}

const registry = new Map<LazyPageName, PagePreloadEntry>();
let listening = false;

export function internalPathnameOf(href: string, origin: string): string | null {
  try {
    const url = new URL(href, origin);
    return url.origin === origin ? url.pathname : null;
  } catch {
    return null;
  }
}

export function pageNameFor(entries: Iterable<PagePreloadEntry>, pathname: string): LazyPageName | null {
  for (const entry of entries) {
    if (entry.paths.some((path) => matchPath(path, pathname))) return entry.name;
  }
  return null;
}

export function preloadPage(name: LazyPageName): void {
  registry.get(name)?.preload();
}

export function preloadPageFor(pathname: string): void {
  const name = pageNameFor(registry.values(), pathname);
  if (name) preloadPage(name);
}

function preloadLinkTarget(event: Event): void {
  if (!(event.target instanceof Element)) return;
  const link = event.target.closest("a[href]");
  if (!(link instanceof HTMLAnchorElement)) return;
  const pathname = internalPathnameOf(link.href, window.location.origin);
  if (pathname) preloadPageFor(pathname);
}

export function initPagePreload(entries: readonly PagePreloadEntry[]): void {
  for (const entry of entries) registry.set(entry.name, entry);
  preloadPageFor(window.location.pathname);
  if (listening) return;
  listening = true;
  document.addEventListener("pointerdown", preloadLinkTarget, { capture: true, passive: true });
}

function whenIdle(task: () => void): void {
  if (typeof window.requestIdleCallback === "function") window.requestIdleCallback(task);
  else window.setTimeout(task, 1);
}

function whenWindowLoaded(task: () => void): void {
  if (document.readyState === "complete") {
    task();
    return;
  }
  window.addEventListener("load", task, { once: true });
}

export function prefetchWhenIdle(names: readonly LazyPageName[]): void {
  whenWindowLoaded(() => {
    whenIdle(() => {
      for (const name of names) preloadPage(name);
    });
  });
}
