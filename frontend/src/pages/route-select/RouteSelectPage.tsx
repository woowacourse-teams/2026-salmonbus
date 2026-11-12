import { useState } from "react";
import { matchPath, Outlet, useLocation } from "react-router";
import { mapFlowFrom } from "@/shared/routing/boardEntry";
import { paths } from "@/shared/routing/paths";
import { RouteSelectScreen, type FavoriteGroupSelection } from "./components/RouteSelectScreen";

export function RouteSelectPage() {
  const location = useLocation();
  const [favoriteSelection, setFavoriteSelection] = useState<FavoriteGroupSelection | null>(null);
  const board = matchPath(paths.board, location.pathname) !== null;
  const search = location.pathname === paths.routeSearch;
  if ((board || search) && !mapFlowFrom(location.state)) return <Outlet />;
  return <RouteSelectScreen favoriteSelection={favoriteSelection} onFavoriteSelection={setFavoriteSelection} />;
}
