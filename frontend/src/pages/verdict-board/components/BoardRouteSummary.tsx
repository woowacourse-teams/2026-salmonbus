import { useEffect, useState } from "react";
import type { Board, DirectionInfo } from "@/shared/api/routeForecast.types";
import { headwayLabelFor } from "./displayPolicy";
import clockIcon from "../assets/route-summary-icons/clock.svg";
import timerIcon from "../assets/route-summary-icons/timer.svg";
import busIcon from "../assets/route-summary-icons/bus.svg";
import * as styles from "./BoardRouteSummary.css";

interface BoardRouteSummaryProps {
  board: Board;
  direction: DirectionInfo;
}

export function BoardRouteSummary({ board, direction }: BoardRouteSummaryProps) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div className={styles.summary}>
      <h1 className={styles.routeNumber}>{board.route.displayName}</h1>
      <div className={styles.content}>
        <p className={styles.endpoints}>
          {board.route.startStopName} ↔ {board.route.endStopName}
        </p>
        <dl className={styles.details}>
          <div className={styles.detail}>
            <dt className={styles.label}>운행 시간</dt>
            <dd className={styles.value}>
              <img className={styles.summaryIcon} src={clockIcon} alt="" draggable={false} />
              <span>
                {direction.firstDepartureTime}–{direction.lastDepartureTime}
              </span>
            </dd>
          </div>
          <div className={styles.detail}>
            <dt className={styles.label}>배차 간격</dt>
            <dd className={styles.value}>
              <img className={styles.summaryIcon} src={timerIcon} alt="" draggable={false} />
              <span>{headwayLabelFor(board.route.headways, now)}</span>
            </dd>
          </div>
          <div className={styles.detail}>
            <dt className={styles.label}>현재 운행</dt>
            <dd className={styles.value}>
              <img className={styles.summaryIcon} src={busIcon} alt="" draggable={false} />
              <span>
                <strong>{board.vehiclesInService}</strong>대 운행 중
              </span>
            </dd>
          </div>
        </dl>
      </div>
    </div>
  );
}
