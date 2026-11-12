import { useLayoutEffect } from "react";
import { matchPath, useLocation } from "react-router";
import { sendToApp } from "@/shared/bridge/bridge";
import { paths } from "@/shared/routing/paths";

const tabRoots = [paths.routeSelect, paths.routeMap, paths.favorites, paths.mypage];

export function AppHistorySwipe() {
  const { pathname } = useLocation();
  const allowed = !tabRoots.some((root) => matchPath(root, pathname) !== null);

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
