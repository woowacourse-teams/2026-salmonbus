import type { SeatLevel } from "@/shared/utils/seatGrade";
import * as styles from "./SeatCountBadge.css";

interface SeatCountBadgeProps {
  tone: SeatLevel | "unavailable";
  label: string;
}

export function SeatCountBadge({ tone, label }: SeatCountBadgeProps) {
  return <span className={styles.badge[tone]}>{label}</span>;
}
