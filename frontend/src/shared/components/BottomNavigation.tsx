import type { MouseEvent, Ref } from "react";
import { NavLink, useLocation } from "react-router";
import { appHostFrom } from "@/shared/bridge/bridge";
import { bottomTabs } from "@/shared/routing/bottomTabs";
import { TabPageGlyph } from "./TabPageGlyph";
import * as styles from "./BottomNavigation.css";

export function BottomNavigation({ ref, onActiveTabPress }: { ref?: Ref<HTMLElement>; onActiveTabPress?: () => void }) {
  const { pathname } = useLocation();
  const tabNavigation = appHostFrom() === null ? {} : { replace: true };
  const activeTab =
    bottomTabs.find(
      ({ id, path }) =>
        pathname === path || ((id === "favorites" || id === "mypage") && pathname.startsWith(`${path}/`)),
    )?.id ?? "none";
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
      {bottomTabs.map(({ id, path, label }) => (
        <NavLink
          key={id}
          to={path}
          end={id === "home"}
          {...tabNavigation}
          className={styles.tab}
          aria-label={label}
          onClick={handleTabClick}
        >
          <TabPageGlyph page={id} />
        </NavLink>
      ))}
    </nav>
  );
}
