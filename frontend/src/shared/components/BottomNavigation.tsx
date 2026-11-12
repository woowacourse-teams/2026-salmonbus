import type { MouseEvent, Ref } from "react";
import { NavLink, useLocation } from "react-router";
import { appHostFrom } from "@/shared/bridge/bridge";
import { paths } from "@/shared/routing/paths";
import { TabPageGlyph } from "./TabPageGlyph";
import * as styles from "./BottomNavigation.css";

export function BottomNavigation({ ref, onActiveTabPress }: { ref?: Ref<HTMLElement>; onActiveTabPress?: () => void }) {
  const { pathname } = useLocation();
  const tabNavigation = appHostFrom() === null ? {} : { replace: true };
  const activeTab =
    pathname === "/"
      ? "home"
      : pathname === paths.routeMap
        ? "route-map"
        : pathname === paths.favorites || pathname.startsWith(`${paths.favorites}/`)
          ? "favorites"
          : pathname === "/mypage" || pathname.startsWith("/mypage/")
            ? "mypage"
            : "none";
  function handleTabClick(event: MouseEvent<HTMLAnchorElement>) {
    if (
      !onActiveTabPress ||
      event.currentTarget.pathname !== pathname ||
      event.button !== 0 ||
      event.metaKey ||
      event.ctrlKey ||
      event.shiftKey ||
      event.altKey
    )
      return;
    event.preventDefault();
    onActiveTabPress();
  }

  return (
    <nav ref={ref} className={styles.navigation} aria-label="주요 메뉴" data-active={activeTab} data-native-map-hit>
      <span className={styles.indicator} data-tab-indicator="" aria-hidden="true" />
      <NavLink to="/" end {...tabNavigation} className={styles.tab} aria-label="홈" onClick={handleTabClick}>
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path data-tab-fill="" fillRule="evenodd" d="m3 10 9-7 9 7v10H3Z M9 20v-7h6v7Z" />
        </svg>
      </NavLink>
      <NavLink
        to={paths.routeMap}
        {...tabNavigation}
        className={styles.tab}
        aria-label="노선도"
        onClick={handleTabClick}
      >
        <TabPageGlyph page="routes" />
      </NavLink>
      <NavLink
        to={paths.favorites}
        {...tabNavigation}
        className={styles.tab}
        aria-label="즐겨찾기"
        onClick={handleTabClick}
      >
        <TabPageGlyph page="favorites" />
      </NavLink>
      <NavLink
        to={paths.mypage}
        {...tabNavigation}
        className={styles.tab}
        aria-label="마이페이지"
        onClick={handleTabClick}
      >
        <TabPageGlyph page="profile" />
      </NavLink>
    </nav>
  );
}
