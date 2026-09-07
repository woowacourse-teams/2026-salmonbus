import { Link } from "react-router";
import { paths } from "@/shared/routing/paths";
import * as styles from "./BoardHeader.css";

interface BoardHeaderProps {
  routeName: string;
}

export function BoardHeader({ routeName }: BoardHeaderProps) {
  return (
    <header className={styles.header}>
      <Link to={paths.routeSelect} className={styles.back} aria-label="노선 선택으로 돌아가기">
        <BackArrow />
      </Link>
      <h1 className={styles.routeName}>{routeName}</h1>
    </header>
  );
}

function BackArrow() {
  return (
    <svg
      className={styles.backIcon}
      width="17"
      height="17"
      viewBox="0 0 17 17"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.7}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <path d="M13.6 8.5H3.4M7.9 4 3.4 8.5 7.9 13" />
    </svg>
  );
}
