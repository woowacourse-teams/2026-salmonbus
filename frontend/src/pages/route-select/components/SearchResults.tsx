import type { RouteSummary } from "@/shared/api/routeForecast.types";
import type { SearchView } from "../displayPolicy";
import {
  LOAD_FAILED_NOTICE,
  LOADING_NOTICE,
  RETRY_LABEL,
  SEARCH_RESULTS_LABEL,
  UNMATCHED_NOTICE,
  matchedCountLabel,
  routeButtonLabel,
  searchStatusLabel,
} from "../routeSelectCopy";
import * as styles from "./SearchResults.css";

interface SearchResultsProps {
  view: SearchView;
  onSelect: (route: RouteSummary, position: number) => void;
  onRetry: () => void;
}

export function SearchResults({ view, onSelect, onRetry }: SearchResultsProps) {
  return (
    <>
      {/* 화면 낭독기는 이 문구로 결과 변화를 듣는다. 보이는 문구는 두 번 읽히지 않게 aria-hidden */}
      <p className={styles.status} role="status">
        {searchStatusLabel(view)}
      </p>
      {renderView(view, onSelect, onRetry)}
    </>
  );
}

function renderView(view: SearchView, onSelect: SearchResultsProps["onSelect"], onRetry: () => void) {
  switch (view.kind) {
    case "idle":
      return null;
    case "loading":
      return (
        <p className={styles.loadingNotice} aria-hidden="true">
          {LOADING_NOTICE}
        </p>
      );
    case "error":
      return (
        <div className={styles.notice}>
          <p aria-hidden="true">{LOAD_FAILED_NOTICE}</p>
          <button className={styles.retryButton} type="button" onClick={onRetry}>
            {RETRY_LABEL}
          </button>
        </div>
      );
    case "unmatched":
      return (
        <p className={styles.notice} aria-hidden="true">
          {UNMATCHED_NOTICE}
        </p>
      );
    case "matched":
      return (
        <section className={styles.sheet} aria-label={SEARCH_RESULTS_LABEL}>
          <p className={styles.count} aria-hidden="true">
            {matchedCountLabel(view.routes.length)}
          </p>
          <ul className={styles.list}>
            {view.routes.map((route, position) => (
              <li key={route.id}>
                <button
                  className={styles.item}
                  type="button"
                  aria-label={routeButtonLabel(route)}
                  onClick={() => onSelect(route, position)}
                >
                  <span className={styles.itemText}>
                    <span className={styles.routeNumber}>{route.displayName}</span>
                    <span className={styles.stops}>
                      {route.startStopName} → {route.endStopName}
                    </span>
                  </span>
                  <svg className={styles.chevron} viewBox="0 0 24 24" aria-hidden="true">
                    <path d="m9 6 6 6-6 6" />
                  </svg>
                </button>
              </li>
            ))}
          </ul>
        </section>
      );
  }
}
