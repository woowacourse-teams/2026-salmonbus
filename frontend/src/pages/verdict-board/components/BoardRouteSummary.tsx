import type { Board, DirectionInfo } from "@/shared/api/routeForecast.types";
import * as styles from "./BoardRouteSummary.css";

interface BoardRouteSummaryProps {
  board: Board;
  direction: DirectionInfo;
  headwayLabel: string;
}

export function BoardRouteSummary({ board, direction, headwayLabel }: BoardRouteSummaryProps) {
  return (
    <div className={styles.summary}>
      <div className={styles.route}>
        <h1 className={styles.routeNumber}>{board.route.displayName}</h1>
        <p className={styles.endpoints}>
          {board.route.startStopName} ↔ {board.route.endStopName}
        </p>
      </div>
      <dl className={styles.details}>
        <div className={styles.detail}>
          <dt className={styles.label}>
            <svg
              className={styles.summaryIcon}
              width="12"
              height="12"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="12" cy="12" r="10" />
              <path d="M12 6v6h4" />
            </svg>
            운행 시간
          </dt>
          <dd className={styles.value}>
            {direction.firstDepartureTime}–{direction.lastDepartureTime}
          </dd>
        </div>
        <div className={styles.detail}>
          <dt className={styles.label}>
            <svg
              className={styles.summaryIcon}
              width="12"
              height="12"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <circle cx="12" cy="14" r="8" />
              <path d="M10 2h4M12 10v4M19 7l1-1" />
            </svg>
            배차 간격
          </dt>
          <dd className={styles.value}>{headwayLabel}</dd>
        </div>
        <div className={styles.detail}>
          <dt className={styles.label}>
            <svg
              className={styles.summaryIcon}
              width="12"
              height="12"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <rect x="4" y="3" width="16" height="16" rx="2" />
              <path d="M4 10h16M8 19v2M16 19v2M2 7h2M20 7h2M8 14h.01M16 14h.01" />
            </svg>
            현재 운행
          </dt>
          <dd className={styles.value}>{board.vehiclesInService}대</dd>
        </div>
      </dl>
    </div>
  );
}
