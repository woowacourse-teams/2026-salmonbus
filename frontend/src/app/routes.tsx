import { Route, Routes } from "react-router";
import { RouteSelectPage } from "@/pages/route-select";
import { VerdictBoardPage } from "@/pages/verdict-board";
import { paths } from "@/shared/routing/paths";
import { LoginPage } from "@/pages/login";
import { MyPage } from "@/pages/my-page";
import { NicknamePage } from "@/pages/nickname";

export function AppRoutes() {
  return (
    <Routes>
      <Route path={paths.routeSelect} element={<RouteSelectPage />} />
      <Route path={paths.board} element={<VerdictBoardPage />} />
      <Route path={paths.login} element={<LoginPage />} />
      <Route path={paths.mypage} element={<MyPage />} />
      <Route path={paths.nickname} element={<NicknamePage />} />
    </Routes>
  );
}
