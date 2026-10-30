import { NavLink, useLocation } from "react-router";
import * as styles from "./BottomNavigation.css";

export function BottomNavigation() {
  const { pathname } = useLocation();
  const activeTab =
    pathname === "/" ? "home" : pathname === "/mypage" || pathname.startsWith("/mypage/") ? "mypage" : "none";
  return (
    <nav className={styles.navigation} aria-label="주요 메뉴" data-active={activeTab}>
      <span className={styles.indicator} data-tab-indicator="" aria-hidden="true" />
      <NavLink to="/" end className={styles.tab} aria-label="홈">
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
          <path d="m3 10 9-7 9 7v10H3Z M9 20v-7h6v7" />
        </svg>
      </NavLink>
      <NavLink to="/mypage" className={styles.tab} aria-label="마이페이지">
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
          <circle cx="12" cy="7" r="3.5" />
          <path d="M5 21v-2a7 7 0 0 1 14 0v2" />
        </svg>
      </NavLink>
    </nav>
  );
}
