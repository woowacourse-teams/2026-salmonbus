import { matchPath, Outlet, useLocation } from "react-router";
import { mapFlowFrom } from "@/shared/routing/boardEntry";
import { paths } from "@/shared/routing/paths";
import { RouteSelectScreen } from "./components/RouteSelectScreen";

export function RouteSelectPage() {
  const location = useLocation();
  const board = matchPath(paths.board, location.pathname) !== null;
  const search = location.pathname === paths.routeSearch;
  if ((board || search) && !mapFlowFrom(location.state)) return <Outlet />;
  return <RouteSelectScreen />;
}
