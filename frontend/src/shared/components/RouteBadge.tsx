import * as styles from "./RouteBadge.css";

interface RouteBadgeProps {
  children: string;
  className?: string;
}

export function RouteBadge({ children, className = "" }: RouteBadgeProps) {
  return <span className={`${styles.badge} ${className}`}>{children}</span>;
}
