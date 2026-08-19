import { SEAT_LEVEL_LABELS, type SeatLevel } from "../displayPolicy";
import * as styles from "./BoardingChip.css";

interface BoardingChipProps {
  level: SeatLevel;
}

export function BoardingChip({ level }: BoardingChipProps) {
  return <span className={styles.chip[level]}>{SEAT_LEVEL_LABELS[level]}</span>;
}
