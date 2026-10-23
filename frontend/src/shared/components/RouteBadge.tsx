import * as styles from "./RouteBadge.css";

interface RouteBadgeProps {
  children: string;
}

export function RouteBadge({ children }: RouteBadgeProps) {
  return <span className={styles.badge}>{children}</span>;
}
