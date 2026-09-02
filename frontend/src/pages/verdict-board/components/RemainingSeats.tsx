import type { SeatInfo } from "@/shared/api/routeForecast.types";
import * as styles from "./RemainingSeats.css";

interface RemainingSeatsProps {
  seat: SeatInfo;
}

export function RemainingSeats({ seat }: RemainingSeatsProps) {
  const remainingSeatLabel = seat.kind === "EXACT" ? `${seat.remaining}석` : "–";

  return (
    <span className={styles.badge}>
      <span className={styles.label}>현재</span>
      <strong className={styles.value}>{remainingSeatLabel}</strong>
    </span>
  );
}
