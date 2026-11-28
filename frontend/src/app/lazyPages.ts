import { createPageLoader } from "@/shared/routing/pageLoader";
import { paths } from "@/shared/routing/paths";
import { pageNameFor, type LazyPageName, type PagePreloadEntry } from "@/shared/routing/pagePreload";
import { createLazyPage } from "./lazyPage";

const favoritesGroup = createPageLoader(() => import("./lazyGroups/favorites"));
const accountGroup = createPageLoader(() => import("./lazyGroups/account"));

export const favoritesPage = createLazyPage("favorites", favoritesGroup, (group) => group.FavoritesPage);
export const myPage = createLazyPage("mypage", accountGroup, (group) => group.MyPage);
export const loginPage = createLazyPage("login", accountGroup, (group) => group.LoginPage);
export const nicknamePage = createLazyPage("nickname", accountGroup, (group) => group.NicknamePage);
export const whatsNewPage = createLazyPage("whats-new", accountGroup, (group) => group.WhatsNewPage);

export const lazyPagePreloads: readonly PagePreloadEntry[] = [
  { name: "favorites", paths: [paths.favorites], preload: favoritesPage.preload },
  { name: "mypage", paths: [paths.mypage], preload: myPage.preload },
  { name: "login", paths: [paths.login], preload: loginPage.preload },
  { name: "nickname", paths: [paths.nickname], preload: nicknamePage.preload },
  { name: "whats-new", paths: [paths.whatsNew], preload: whatsNewPage.preload },
];

export const idlePrefetchPages: readonly LazyPageName[] = ["favorites", "mypage"];

const initialLoads: Record<LazyPageName, () => Promise<unknown>> = {
  favorites: favoritesGroup.load,
  mypage: accountGroup.load,
  login: accountGroup.load,
  nickname: accountGroup.load,
  "whats-new": accountGroup.load,
};

export function loadInitialPage(pathname: string): Promise<void> {
  const name = pageNameFor(lazyPagePreloads, pathname);
  if (name === null) return Promise.resolve();
  return initialLoads[name]().then(
    () => undefined,
    () => undefined,
  );
}
