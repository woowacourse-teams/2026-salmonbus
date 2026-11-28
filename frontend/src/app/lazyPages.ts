import { createPageLoader } from "@/shared/routing/pageLoader";
import { paths } from "@/shared/routing/paths";
import { pageNameFor, type LazyPageName, type PagePreloadEntry } from "@/shared/routing/pagePreload";
import { createLazyPage, type LazyPage } from "./createLazyPage";

const favoritesGroup = createPageLoader(() => import("./lazyGroups/favorites"));
const accountGroup = createPageLoader(() => import("./lazyGroups/account"));

export const favoritesPage = createLazyPage("favorites", favoritesGroup, (group) => group.FavoritesPage);
export const myPage = createLazyPage("mypage", accountGroup, (group) => group.MyPage);
export const loginPage = createLazyPage("login", accountGroup, (group) => group.LoginPage);
export const nicknamePage = createLazyPage("nickname", accountGroup, (group) => group.NicknamePage);
export const whatsNewPage = createLazyPage("whats-new", accountGroup, (group) => group.WhatsNewPage);

function preloadEntryFor(page: LazyPage, routePaths: readonly string[]): PagePreloadEntry {
  return { name: page.name, paths: routePaths, preload: page.preload, load: page.load };
}

export const lazyPagePreloads: readonly PagePreloadEntry[] = [
  preloadEntryFor(favoritesPage, [paths.favorites]),
  preloadEntryFor(myPage, [paths.mypage]),
  preloadEntryFor(loginPage, [paths.login]),
  preloadEntryFor(nicknamePage, [paths.nickname]),
  preloadEntryFor(whatsNewPage, [paths.whatsNew]),
];

export const idlePrefetchPages: readonly LazyPageName[] = ["favorites", "mypage"];

export function loadInitialPage(pathname: string): Promise<void> {
  const name = pageNameFor(lazyPagePreloads, pathname);
  const entry = lazyPagePreloads.find((candidate) => candidate.name === name);
  if (!entry) return Promise.resolve();
  return entry.load().then(
    () => undefined,
    () => undefined,
  );
}
