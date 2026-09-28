import type { ArrivalTone } from "../arrivalPolicy";
import * as styles from "./SeatCountBadge.css";

interface SeatCountBadgeProps {
  tone: ArrivalTone;
  label: string;
}

export function SeatCountBadge({ tone, label }: SeatCountBadgeProps) {
  return <span className={styles.badge[tone]}>{label}</span>;
}
