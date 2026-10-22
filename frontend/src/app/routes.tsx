import { Route, Routes } from "react-router";
import { RouteSelectPage } from "@/pages/route-select";
import { VerdictBoardPage } from "@/pages/verdict-board";
import { paths } from "@/shared/routing/paths";
import { LoginPage } from "@/pages/account/LoginPage";
import { MyPage } from "@/pages/account/MyPage";
import { NicknamePage } from "@/pages/account/NicknamePage";

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
