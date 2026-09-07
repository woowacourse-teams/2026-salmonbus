import { chipLabel, type ChipTone } from "../verdictCopy";
import * as styles from "./VerdictChip.css";

interface VerdictChipProps {
  tone: ChipTone;
}

export function VerdictChip({ tone }: VerdictChipProps) {
  return <span className={styles.chip[tone]}>{chipLabel(tone)}</span>;
}
