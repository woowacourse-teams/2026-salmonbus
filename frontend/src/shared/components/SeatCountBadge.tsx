import type { SeatLevel } from "@/shared/utils/seatGrade";
import * as styles from "./SeatCountBadge.css";

interface SeatCountBadgeProps {
  tone: SeatLevel | "unavailable";
  label: string;
  className?: string;
}

export function SeatCountBadge({ tone, label, className = "" }: SeatCountBadgeProps) {
  return <span className={`${styles.badge[tone]} ${className}`}>{label}</span>;
}
