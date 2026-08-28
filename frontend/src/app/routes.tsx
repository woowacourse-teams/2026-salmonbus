import { Route, Routes } from "react-router";
import { RouteSelectPage } from "@/pages/route-select";
import { VerdictBoardPage } from "@/pages/verdict-board";
import { paths } from "@/shared/routing/paths";

export function AppRoutes() {
  return (
    <Routes>
      <Route path={paths.routeSelect} element={<RouteSelectPage />} />
      <Route path={paths.board} element={<VerdictBoardPage />} />
    </Routes>
  );
}
