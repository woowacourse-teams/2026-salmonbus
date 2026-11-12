import { useLayoutEffect } from "react";
import { matchPath, useLocation } from "react-router";
import { sendToApp } from "@/shared/bridge/bridge";
import { bottomTabs } from "@/shared/routing/bottomTabs";

export function AppHistorySwipe() {
  const { pathname } = useLocation();
  const allowed = !bottomTabs.some(({ path }) => matchPath(path, pathname) !== null);

  useLayoutEffect(() => {
    const sync = () => sendToApp("navigation.setHistorySwipe", { allowed });
    const restore = (event: PageTransitionEvent) => {
      if (event.persisted) sync();
    };
    sync();
    window.addEventListener("pageshow", restore);
    return () => window.removeEventListener("pageshow", restore);
  }, [allowed]);

  return null;
}
