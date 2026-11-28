export const STARTUP_MARKS = {
  webEntry: "salmon:web:entry",
  webFirstCommit: "salmon:web:first-commit",
  webShellCommit: "salmon:web:shell-commit",
  mapSdkStart: "salmon:map:sdk-start",
  mapSdkReady: "salmon:map:sdk-ready",
  mapCreate: "salmon:map:create",
  mapInit: "salmon:map:init",
  mapNativeMount: "salmon:map:native-mount",
  mapNativeReady: "salmon:map:native-ready",
  mapNativeError: "salmon:map:native-error",
  mapWebTransparent: "salmon:map:web-transparent",
  mapStopsInteractive: "salmon:map:stops-interactive",
} as const;

export type StartupMarkName = (typeof STARTUP_MARKS)[keyof typeof STARTUP_MARKS];

export interface StartupMarker {
  mark: (name: StartupMarkName) => boolean;
}

export function createStartupMarker(clock: Pick<Performance, "mark"> | undefined): StartupMarker {
  const marked = new Set<StartupMarkName>();
  return {
    mark(name) {
      if (clock === undefined || marked.has(name)) return false;
      marked.add(name);
      try {
        clock.mark(name);
        return true;
      } catch {
        return false;
      }
    },
  };
}

export const startupMarker: StartupMarker = createStartupMarker(globalThis.performance);

const pendingDomChecks = new Set<() => void>();

export function flushStartupDom() {
  pendingDomChecks.forEach((check) => check());
}

const HOME_MAP_TRANSPARENT_ATTRIBUTE = "data-native-home-map";

function isTransparent(html: HTMLElement): boolean {
  return html.getAttribute(HOME_MAP_TRANSPARENT_ATTRIBUTE) === "true";
}

export function observeStartupDom(
  root: HTMLElement,
  html: HTMLElement = document.documentElement,
  marker: StartupMarker = startupMarker,
): () => void {
  if (typeof MutationObserver === "undefined") return () => undefined;
  let firstCommit = false;
  let shellCommit = false;
  const checkRoot = () => {
    if (!firstCommit && root.firstChild !== null) {
      firstCommit = true;
      marker.mark(STARTUP_MARKS.webFirstCommit);
    }
    if (!shellCommit && root.querySelector("main") !== null) {
      shellCommit = true;
      marker.mark(STARTUP_MARKS.webShellCommit);
    }
    return firstCommit && shellCommit;
  };
  const checkHtml = () => {
    if (!isTransparent(html)) return false;
    marker.mark(STARTUP_MARKS.mapWebTransparent);
    return true;
  };
  const rootObserver = new MutationObserver(() => flushRoot());
  const htmlObserver = new MutationObserver(() => flushHtml());
  const flushRoot = () => {
    if (!checkRoot()) return;
    rootObserver.disconnect();
    pendingDomChecks.delete(flushRoot);
  };
  const flushHtml = () => {
    if (!checkHtml()) return;
    htmlObserver.disconnect();
    pendingDomChecks.delete(flushHtml);
  };
  if (!checkRoot()) {
    rootObserver.observe(root, { childList: true, subtree: true });
    pendingDomChecks.add(flushRoot);
  }
  if (!checkHtml()) {
    htmlObserver.observe(html, { attributes: true, attributeFilter: [HOME_MAP_TRANSPARENT_ATTRIBUTE] });
    pendingDomChecks.add(flushHtml);
  }
  return () => {
    rootObserver.disconnect();
    htmlObserver.disconnect();
    pendingDomChecks.delete(flushRoot);
    pendingDomChecks.delete(flushHtml);
  };
}
