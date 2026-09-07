import type { SeatLevel } from "../seatGrade";
import * as styles from "./SeatCountBadge.css";

interface SeatCountBadgeProps {
  level: SeatLevel;
  label: string;
}

export function SeatCountBadge({ level, label }: SeatCountBadgeProps) {
  return <span className={styles.badge[level]}>{label}</span>;
}
