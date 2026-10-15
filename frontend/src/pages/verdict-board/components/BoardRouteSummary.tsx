import type { Board, DirectionInfo } from "@/shared/api/routeForecast.types";
import clockIcon from "./route-summary-icons/clock.svg";
import timerIcon from "./route-summary-icons/timer.svg";
import busIcon from "./route-summary-icons/bus.svg";
import * as styles from "./BoardRouteSummary.css";

interface BoardRouteSummaryProps {
  board: Board;
  direction: DirectionInfo;
  headwayLabel: string;
}

export function BoardRouteSummary({ board, direction, headwayLabel }: BoardRouteSummaryProps) {
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
              <span>{headwayLabel}</span>
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
