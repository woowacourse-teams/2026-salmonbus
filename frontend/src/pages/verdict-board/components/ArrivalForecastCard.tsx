import { useEffect, useReducer } from "react";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { stopsAwayLabelOf } from "@/shared/utils/stopsAwayLabelOf";
import type { ArrivalView } from "./arrivalPolicy";
import { stationArrivalPanelFor, type StationArrivalDetails } from "./stationArrivalPolicy";
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

  const panel = stationArrivalPanelFor(stationArrivals, arrivals);
  if (panel.kind !== "ready") {
    const notice = {
      loading: "도착정보 확인 중이에요",
      unavailable: "도착정보를 불러오지 못했어요",
      stale: "도착정보가 만료됐어요. 갱신을 기다리고 있어요",
      empty: "현재 도착 예정 차량이 없어요",
    }[panel.kind];
    return (
      <div className={styles.card}>
        <p className={styles.notice}>{notice}</p>
      </div>
    );
  }

  return (
    <div className={styles.card}>
      <ul className={styles.list}>
        {panel.arrivals.map((arrival) => {
          const { timing, remainingStops } = arrival;
          return (
            <li key={arrival.ordinal} className={styles.row}>
              <span className={styles.timing}>
                <span className={remainingStops === 0 ? styles.remainingTime : styles.distance}>
                  {remainingStops === null ? "거리 정보 없음" : stopsAwayLabelOf(remainingStops)}
                </span>
                {timing && (
                  <span className={timing.available ? styles.remainingTime : styles.unavailableTime}>
                    {timing.label}
                  </span>
                )}
              </span>
              <SeatCountBadge tone={arrival.tone} label={arrival.seatLabel} />
            </li>
          );
        })}
      </ul>
    </div>
  );
}
