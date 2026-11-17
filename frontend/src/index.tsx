import { errorTrackingRootOptions } from "@/app/initErrorTracking";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@/shared/styles/reset.css";
import { App } from "@/app/App";
import { AppErrorBoundary } from "@/app/AppErrorBoundary";
import { initAnalytics } from "@/shared/analytics/analytics";
import { initInputMethod } from "@/shared/input-method/inputMethod";
import { initOpenedNotifications } from "@/shared/notification/openedNotificationInbox";
import { initPagePreload, prefetchWhenIdle } from "@/shared/routing/pagePreload";
import { idlePrefetchPages, lazyPagePreloads, loadInitialPage } from "@/app/lazyPages";

const container = document.getElementById("root");
if (!container) {
  throw new Error("root 요소를 찾을 수 없습니다");
}

initAnalytics();
initInputMethod();
initOpenedNotifications();
initPagePreload(lazyPagePreloads);
prefetchWhenIdle(idlePrefetchPages);

const root = createRoot(container, errorTrackingRootOptions);

void loadInitialPage(window.location.pathname).then(() =>
  root.render(
    <StrictMode>
      <AppErrorBoundary>
        <App />
      </AppErrorBoundary>
    </StrictMode>,
  ),
);
