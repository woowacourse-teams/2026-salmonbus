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
  mark: (name: StartupMarkName) => void;
}

export function createStartupMarker(clock: Pick<Performance, "mark"> | undefined): StartupMarker {
  const marked = new Set<StartupMarkName>();
  return {
    mark(name) {
      if (clock === undefined || marked.has(name)) return;
      marked.add(name);
      try {
        clock.mark(name);
      } catch {
        return;
      }
    },
  };
}

export const startupMarker: StartupMarker = createStartupMarker(globalThis.performance);

const TRANSPARENT_ATTRIBUTES = ["data-native-home-map", "data-shared-native-map-ready"] as const;

function isTransparent(html: HTMLElement): boolean {
  return TRANSPARENT_ATTRIBUTES.some((attribute) => html.getAttribute(attribute) === "true");
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
  const rootObserver = new MutationObserver(() => {
    if (checkRoot()) rootObserver.disconnect();
  });
  const htmlObserver = new MutationObserver(() => {
    if (checkHtml()) htmlObserver.disconnect();
  });
  if (!checkRoot()) rootObserver.observe(root, { childList: true, subtree: true });
  if (!checkHtml()) htmlObserver.observe(html, { attributes: true, attributeFilter: [...TRANSPARENT_ATTRIBUTES] });
  return () => {
    rootObserver.disconnect();
    htmlObserver.disconnect();
  };
}
