import type { PageErrorContext } from "@/shared/error-tracking/eventPolicy";
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
