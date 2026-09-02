import type { Phase, SeatInfo, Vehicle } from "@/shared/api/routeForecast.types";
import { RemainingSeats } from "./RemainingSeats";
import * as styles from "./LiveBusTraker.css";

interface LiveBusTrakerProps {
  vehicle: Vehicle;
}

const phaseDescription: Record<Phase, string> = {
  ARRIVING: "도착 중",
  DEPARTED: "출발함",
  IN_TRANSIT: "다음 정류장으로 이동 중",
};

function describeRemainingSeats(seat: SeatInfo) {
  return seat.kind === "EXACT" ? `현재 잔여 좌석 ${seat.remaining}석` : "현재 잔여 좌석 정보 없음";
}

export function LiveBusTraker({ vehicle }: LiveBusTrakerProps) {
  const accessibilityLabel = `${vehicle.stopName} 정류장, ${phaseDescription[vehicle.phase]}, ${describeRemainingSeats(vehicle.seat)}`;

  return (
    <div className={styles.tracker} role="img" aria-label={accessibilityLabel}>
      <span className={styles.remainingSeats} aria-hidden="true">
        <RemainingSeats seat={vehicle.seat} />
      </span>

      <svg
        className={styles.busIcon}
        width="30"
        height="27"
        viewBox="0 0 30 27"
        fill="none"
        focusable="false"
        aria-hidden="true"
      >
        <path
          className={styles.busOutline}
          d="M4.243 6.513H1.678m23.881 0h2.763M7.006 22.895v2.566m16.185-2.566v2.566"
        />
        <rect className={styles.busBody} x="4.243" y="1.382" width="21.316" height="22.105" rx="2.961" />
        <path className={styles.busOutline} d="M4.243 12.237h21.316" />
        <rect className={styles.busDetail} x="11.053" y="4.145" width="7.895" height="2.368" rx="1.184" />
        <circle className={styles.busDetail} cx="9.572" cy="17.368" r="1.382" />
        <circle className={styles.busDetail} cx="20.23" cy="17.368" r="1.382" />
      </svg>
    </div>
  );
}
