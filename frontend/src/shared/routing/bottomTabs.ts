import { paths } from "./paths";

export const bottomTabs = [
  { id: "home", path: paths.routeSelect, label: "홈" },
  { id: "route-map", path: paths.routeMap, label: "노선도" },
  { id: "favorites", path: paths.favorites, label: "즐겨찾기" },
  { id: "mypage", path: paths.mypage, label: "마이페이지" },
] as const;

export type BottomTabId = (typeof bottomTabs)[number]["id"];
