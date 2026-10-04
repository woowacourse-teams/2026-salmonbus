import type { RootOptions } from "react-dom/client";
import { initErrorTracking } from "@/shared/error-tracking/errorTracking";
import type { PageErrorContext } from "@/shared/error-tracking/eventPolicy";
import { initApiFailureReporter } from "@/shared/api/client";
import { paths } from "@/shared/routing/paths";

const boardPathPattern = new RegExp("^" + paths.board.replace(":routeId", "([^/]+)") + "/?$");

export function isErrorTrackingEnabled(
  buildMode: string | undefined,
  environment: string,
  dsn: string,
  hostname: string,
): boolean {
  return (
    buildMode === "production" &&
    environment === "production" &&
    dsn !== "" &&
    !["localhost", "127.0.0.1", "::1", "[::1]", ""].includes(hostname)
  );
}

export function pageErrorContextFor(pathname: string): PageErrorContext {
  const routeId = boardPathPattern.exec(pathname)?.[1];
  return { path: pathname, ...(routeId ? { tags: { route_id: routeId } } : {}) };
}

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
    /* 브라우저가 저장소를 막으면 메모리 제한으로 동작한다. */
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

// App 모듈보다 먼저 import해서 앱 초기 로딩 중 발생하는 오류도 수집한다.
export const errorTrackingRootOptions = initAppErrorTracking();
