import type { RootOptions } from "react-dom/client";
import { initErrorTracking } from "@/shared/error-tracking/errorTracking";
import { initApiFailureReporter } from "@/shared/api/client";
import { isErrorTrackingEnabled, pageErrorContextFor } from "./appErrorTrackingPolicy";

function initAppErrorTracking(): RootOptions {
  if (
    typeof window === "undefined" ||
    !isErrorTrackingEnabled(process.env.NODE_ENV, __SENTRY_ENVIRONMENT__, __SENTRY_DSN__, window.location.hostname)
  ) {
    return {};
  }
  let storage: Storage | undefined;
  try {
    storage = window.sessionStorage;
  } catch {
    /* sessionStorage를 사용할 수 없으면 메모리에 전송 기록을 보관해 중복 전송을 제한합니다. */
  }
  const tracking = initErrorTracking({
    dsn: __SENTRY_DSN__,
    environment: __SENTRY_ENVIRONMENT__,
    release: __APP_RELEASE__,
    ...(storage ? { storage } : {}),
    contextForPage: () => pageErrorContextFor(window.location.pathname),
  });
  initApiFailureReporter(tracking.apiFailureReporter);
  return tracking.reactErrorHandlers;
}

// App 모듈보다 먼저 import해서 앱 초기 로딩 중 발생하는 오류도 수집
export const errorTrackingRootOptions = initAppErrorTracking();
