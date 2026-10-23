import { Route, Routes } from "react-router";
import { RouteSearchPage } from "@/pages/route-search";
import { RouteSelectPage } from "@/pages/route-select";
import { VerdictBoardPage } from "@/pages/verdict-board";
import { paths } from "@/shared/routing/paths";
import { FocusKeepingNavigationContext } from "@/shared/routing/focusKeepingNavigation";
import { LoginPage } from "@/pages/login";
import { MyPage } from "@/pages/my-page";
import { FavoriteGroupPage } from "@/pages/favorite-group";
import { NicknamePage } from "@/pages/nickname";
import { useFocusKeepingNavigation } from "./useFocusKeepingNavigation";

export function AppRoutes() {
  const { shownLocation, navigateKeepingFocus } = useFocusKeepingNavigation();

  // Routes에 location을 늘 넘겨야 미리 그린 화면에서 실제 주소로 바뀔 때 화면이 다시 마운트되지 않는다.
  return (
    <FocusKeepingNavigationContext value={navigateKeepingFocus}>
      <Routes location={shownLocation}>
        <Route path={paths.routeSelect} element={<RouteSelectPage />} />
        <Route path={paths.routeSearch} element={<RouteSearchPage />} />
        <Route path={paths.board} element={<VerdictBoardPage />} />
        <Route path={paths.login} element={<LoginPage />} />
        <Route path={paths.mypage} element={<MyPage />} />
        <Route path={paths.nickname} element={<NicknamePage />} />
        <Route path={paths.favoriteGroup} element={<FavoriteGroupPage />} />
      </Routes>
    </FocusKeepingNavigationContext>
  );
}
