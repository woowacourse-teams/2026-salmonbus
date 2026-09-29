import { useSyncExternalStore } from "react";
import { DESKTOP_QUERY } from "./chatLayout";

function subscribe(onChange: () => void) {
  const query = window.matchMedia(DESKTOP_QUERY);
  query.addEventListener("change", onChange);
  return () => query.removeEventListener("change", onChange);
}

function isDesktopNow() {
  return window.matchMedia(DESKTOP_QUERY).matches;
}

function isDesktopOnServer() {
  return false;
}

export function useIsDesktop(): boolean {
  return useSyncExternalStore(subscribe, isDesktopNow, isDesktopOnServer);
}
