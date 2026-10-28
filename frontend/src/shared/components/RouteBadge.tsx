import * as styles from "./RouteBadge.css";

interface RouteBadgeProps {
  children: string;
  className?: string;
  size?: "small" | "large";
}

export function RouteBadge({ children, className = "", size = "small" }: RouteBadgeProps) {
  return <span className={`${styles.badge} ${size === "large" ? styles.large : ""} ${className}`}>{children}</span>;
}
