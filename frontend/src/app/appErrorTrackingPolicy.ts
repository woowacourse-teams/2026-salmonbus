import { matchRoutes } from "react-router";
import type { PageErrorContext } from "@/shared/error-tracking/eventPolicy";
import { paths } from "@/shared/routing/paths";

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
  const routeId = matchRoutes([{ path: paths.board }], { pathname })?.[0]?.params.routeId;
  return { path: pathname, ...(routeId ? { tags: { route_id: routeId } } : {}) };
}
