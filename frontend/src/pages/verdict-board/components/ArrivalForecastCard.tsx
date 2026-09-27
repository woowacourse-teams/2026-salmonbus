import type { ArrivalView } from "../arrivalPolicy";
import { arrivalToneFor } from "../displayPolicy";
import { NO_VEHICLE_NOTICE, arrivalSeatLabel, stopsAwayLabel } from "../verdictCopy";
import { SeatCountBadge } from "./SeatCountBadge";
import * as styles from "./ArrivalForecastCard.css";

interface ArrivalForecastCardProps {
  arrivals: ArrivalView[];
}

export function ArrivalForecastCard({ arrivals }: ArrivalForecastCardProps) {
  if (arrivals.length === 0) {
    return (
      <div className={styles.card}>
        <p className={styles.notice}>{NO_VEHICLE_NOTICE}</p>
      </div>
    );
  }

  return (
    <div className={styles.card}>
      <ul className={styles.list}>
        {arrivals.map((arrival, index) => (
          <li key={index} className={styles.row}>
            <span className={styles.distance}>{stopsAwayLabel(arrival.stopsAway)}</span>
            <SeatCountBadge tone={arrivalToneFor(arrival)} label={arrivalSeatLabel(arrival)} />
          </li>
        ))}
      </ul>
    </div>
  );
}
