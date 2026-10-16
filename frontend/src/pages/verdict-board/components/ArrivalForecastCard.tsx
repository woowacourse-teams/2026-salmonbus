import { useEffect, useReducer } from "react";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { arrivalToneFor, type ArrivalView } from "./arrivalPolicy";
import { arrivalTimingFor, type StationArrivalDetails } from "./stationArrivalPolicy";
import { NO_VEHICLE_NOTICE, arrivalSeatLabel, stopsAwayLabel } from "./verdictCopy";
import { SeatCountBadge } from "./SeatCountBadge";
import * as styles from "./ArrivalForecastCard.css";

interface ArrivalForecastCardProps {
  arrivals: ArrivalView[];
  stationArrivals: StationArrivalDetails | null;
}

export function ArrivalForecastCard({ arrivals, stationArrivals }: ArrivalForecastCardProps) {
  const [, tick] = useReducer((value: number) => value + 1, 0);
  const latest = stationArrivals === null ? null : latestSuccessOf(stationArrivals.resource);
  useEffect(() => {
    if (latest === null) return;
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [latest]);

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
        {arrivals.map((arrival, index) => {
          const timing = arrivalTimingFor(arrival, stationArrivals);
          return (
            <li key={arrival.vehicleId ?? index} className={styles.row}>
              <span className={styles.timing}>
                <span className={styles.distance}>{stopsAwayLabel(timing.stopsAway)}</span>
                <span className={timing.available ? styles.remainingTime : styles.unavailableTime}>{timing.label}</span>
              </span>
              <SeatCountBadge tone={arrivalToneFor(arrival)} label={arrivalSeatLabel(arrival)} />
            </li>
          );
        })}
      </ul>
    </div>
  );
}
