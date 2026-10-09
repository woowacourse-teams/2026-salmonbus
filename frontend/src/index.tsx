import { errorTrackingRootOptions } from "@/app/initErrorTracking";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@/shared/styles/reset.css";
import { App } from "@/app/App";
import { AppErrorBoundary } from "@/app/AppErrorBoundary";
import { initAnalytics } from "@/shared/analytics/analytics";

const container = document.getElementById("root");
if (!container) {
  throw new Error("root 요소를 찾을 수 없습니다");
}

initAnalytics();

createRoot(container, errorTrackingRootOptions).render(
  <StrictMode>
    <AppErrorBoundary>
      <App />
    </AppErrorBoundary>
  </StrictMode>,
);
