import type { ArrivalView } from "../arrivalPolicy";
import { NO_FORECAST_NOTICE, seatLabel, stopsAwayLabel } from "../verdictCopy";
import { SeatCountBadge } from "./SeatCountBadge";
import * as styles from "./ArrivalForecastCard.css";

interface ArrivalForecastCardProps {
  id: string;
  arrivals: ArrivalView[];
}

export function ArrivalForecastCard({ id, arrivals }: ArrivalForecastCardProps) {
  if (arrivals.length === 0) {
    return (
      <div id={id} className={styles.card}>
        <p className={styles.notice}>{NO_FORECAST_NOTICE}</p>
      </div>
    );
  }

  return (
    <div id={id} className={styles.card}>
      <ul className={styles.list}>
        {arrivals.map((arrival, index) => (
          <li key={index} className={styles.row}>
            <span className={styles.distance}>{stopsAwayLabel(arrival.stopsAway)}</span>
            <SeatCountBadge level={arrival.level} label={seatLabel(arrival.seatEstimate)} />
          </li>
        ))}
      </ul>
    </div>
  );
}
