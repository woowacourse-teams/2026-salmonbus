import { useEffect } from "react";
import { matchPath, Route, Routes } from "react-router";
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

const pages = [
  { path: paths.routeSelect, title: "홈", element: <RouteSelectPage /> },
  { path: paths.routeSearch, title: "노선 검색", element: <RouteSearchPage /> },
  { path: paths.board, title: "노선 판정보드", element: <VerdictBoardPage /> },
  { path: paths.login, title: "로그인", element: <LoginPage /> },
  { path: paths.mypage, title: "마이페이지", element: <MyPage /> },
  { path: paths.nickname, title: "닉네임 수정", element: <NicknamePage /> },
  { path: paths.favoriteGroup, title: "즐겨찾기 그룹 관리", element: <FavoriteGroupPage /> },
];

export function AppRoutes() {
  const { shownLocation, navigateKeepingFocus } = useFocusKeepingNavigation();
  const title = pages.find(({ path }) => matchPath(path, shownLocation.pathname))?.title;

  useEffect(() => {
    document.title = title === undefined ? "연어" : `${title} | 연어`;
  }, [title]);

  // Routes에 location을 늘 넘겨야 미리 그린 화면에서 실제 주소로 바뀔 때 화면이 다시 마운트되지 않는다.
  return (
    <FocusKeepingNavigationContext value={navigateKeepingFocus}>
      <Routes location={shownLocation}>
        {pages.map(({ path, element }) => (
          <Route key={path} path={path} element={element} />
        ))}
      </Routes>
    </FocusKeepingNavigationContext>
  );
}
