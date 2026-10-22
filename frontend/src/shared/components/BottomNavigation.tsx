import { NavLink } from "react-router";
import * as styles from "./BottomNavigation.css";

export function BottomNavigation() {
  return (
    <nav className={styles.navigation} aria-label="주요 메뉴">
      <NavLink to="/" end className={styles.tab}>
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
        >
          <path d="m3 10 9-7 9 7v10H3Z M9 20v-7h6v7" />
        </svg>
        홈
      </NavLink>
      <NavLink to="/mypage" className={styles.tab}>
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
        >
          <circle cx="12" cy="7" r="3.5" />
          <path d="M5 21v-2a7 7 0 0 1 14 0v2" />
        </svg>
        마이페이지
      </NavLink>
    </nav>
  );
}
