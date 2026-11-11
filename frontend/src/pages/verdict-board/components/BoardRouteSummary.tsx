import { useEffect, useId, useState } from "react";
import type { Board, DirectionInfo } from "@/shared/api/routeForecast.types";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { headwayLabelFor } from "./displayPolicy";
import * as styles from "./BoardRouteSummary.css";

interface BoardRouteSummaryProps {
  board: Board;
  direction: DirectionInfo;
}

export function BoardRouteSummary({ board, direction }: BoardRouteSummaryProps) {
  const detailsId = useId();
  const [expanded, setExpanded] = useState(false);
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  return (
    <div className={styles.summary}>
      <button
        type="button"
        className={styles.toggle}
        aria-controls={detailsId}
        aria-expanded={expanded}
        onClick={() => setExpanded((previous) => !previous)}
      >
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" aria-hidden="true">
          <circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeWidth="1.3" />
          <circle cx="8" cy="5" r="0.8" fill="currentColor" />
          <path d="M8 7.5v3.5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
        </svg>
        운행 정보
        <span className={styles.chevron} aria-hidden="true" />
      </button>
      <div id={detailsId} className={styles.detailsPanel} aria-hidden={!expanded}>
        <div className={styles.detailsClip}>
          <dl className={styles.details}>
            <div className={styles.detail}>
              <dt className={styles.label}>운행 시간</dt>
              <dd className={styles.value}>
                {direction.firstDepartureTime}–{direction.lastDepartureTime}
              </dd>
            </div>
            <div className={styles.detail}>
              <dt className={styles.label}>평균 배차</dt>
              <dd className={styles.value}>{headwayLabelFor(board.route.headways, now)}</dd>
            </div>
            <div className={styles.detail}>
              <dt className={visuallyHidden}>현재 운행</dt>
              <dd className={styles.value}>
                <strong>{board.vehiclesInService}</strong>대 운행 중
              </dd>
            </div>
          </dl>
        </div>
      </div>
    </div>
  );
}
