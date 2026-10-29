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

// 화면을 이동한 뒤 코드로 포커스를 옮겨도 마지막 입력 방식은 유지한다.
container.addEventListener("pointerdown", () => (container.dataset.inputMethod = "pointer"), true);
container.addEventListener("keydown", () => (container.dataset.inputMethod = "keyboard"), true);

createRoot(container, errorTrackingRootOptions).render(
  <StrictMode>
    <AppErrorBoundary>
      <App />
    </AppErrorBoundary>
  </StrictMode>,
);
