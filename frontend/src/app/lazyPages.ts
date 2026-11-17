import { createPageLoader } from "@/shared/routing/pageLoader";
import { paths } from "@/shared/routing/paths";
import type { PagePreloadEntry } from "@/shared/routing/pagePreload";
import { createLazyPage } from "./lazyPage";

const boardGroup = createPageLoader(() => import("@/pages/verdict-board"));
const favoritesGroup = createPageLoader(() => import("./lazyGroups/favorites"));
const accountGroup = createPageLoader(() => import("./lazyGroups/account"));
const privacyGroup = createPageLoader(() => import("@/pages/privacy"));

export const boardPage = createLazyPage("board", boardGroup, (group) => group.VerdictBoardPage);
export const favoritesPage = createLazyPage("favorites", favoritesGroup, (group) => group.FavoritesPage);
export const favoriteGroupPage = createLazyPage("favorite-group", favoritesGroup, (group) => group.FavoriteGroupPage);
export const myPage = createLazyPage("mypage", accountGroup, (group) => group.MyPage);
export const loginPage = createLazyPage("login", accountGroup, (group) => group.LoginPage);
export const nicknamePage = createLazyPage("nickname", accountGroup, (group) => group.NicknamePage);
export const whatsNewPage = createLazyPage("whats-new", accountGroup, (group) => group.WhatsNewPage);
export const privacyPage = createLazyPage(
  "privacy",
  privacyGroup,
  (group) => group.PrivacyPage,
  "처리방침을 불러오고 있어요…",
);

export const lazyPagePreloads: readonly PagePreloadEntry[] = [
  { name: "board", paths: [paths.board, paths.routeSearch], preload: boardPage.preload },
  { name: "favorites", paths: [paths.favorites, paths.favoriteGroups], preload: favoritesPage.preload },
  { name: "favorite-group", paths: [paths.favoriteGroup], preload: favoriteGroupPage.preload },
  { name: "mypage", paths: [paths.mypage], preload: myPage.preload },
  { name: "login", paths: [paths.login], preload: loginPage.preload },
  { name: "nickname", paths: [paths.nickname], preload: nicknamePage.preload },
  { name: "whats-new", paths: [paths.whatsNew], preload: whatsNewPage.preload },
  { name: "privacy", paths: [paths.privacy, paths.privacyEdition], preload: privacyPage.preload },
];
