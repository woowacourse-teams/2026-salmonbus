import { useEffect, useReducer } from "react";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { stopsAwayLabelOf } from "@/shared/utils/stopsAwayLabelOf";
import { arrivalToneFor, type ArrivalView } from "./arrivalPolicy";
import { arrivalTimingFor, type StationArrivalDetails } from "./stationArrivalPolicy";
import { NO_VEHICLE_NOTICE, arrivalSeatLabel } from "./verdictCopy";
import { SeatCountBadge } from "@/shared/components/SeatCountBadge";
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
                {timing && <span className={styles.remainingTime}>{timing}</span>}
                <span className={timing === null ? styles.remainingTime : styles.distance}>
                  {stopsAwayLabelOf(arrival.stopsAway)}
                </span>
              </span>
              <SeatCountBadge tone={arrivalToneFor(arrival)} label={arrivalSeatLabel(arrival)} />
            </li>
          );
        })}
      </ul>
    </div>
  );
}
