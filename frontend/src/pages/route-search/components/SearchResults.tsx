import { useId } from "react";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import type { RouteSelection, SearchView, SelectedFrom } from "../displayPolicy";
import {
  IN_SERVICE_TITLE,
  LOAD_FAILED_NOTICE,
  LOADING_NOTICE,
  RETRY_LABEL,
  SEARCH_RESULTS_LABEL,
  UNMATCHED_NOTICE,
  matchedCountLabel,
  routeButtonLabel,
  searchStatusLabel,
} from "../routeSearchCopy";
import * as styles from "./SearchResults.css";

interface SearchResultsProps {
  view: SearchView;
  onSelect: (selection: RouteSelection) => void;
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
    case "browse":
      return <InServiceSection routes={view.inService} onSelect={onSelect} />;
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
          {/* 누르는 순간 검색창 포커스가 빠지면 검색 영역이 내려가서 버튼이 움직이고 클릭이 취소된다 */}
          <button
            className={styles.retryButton}
            type="button"
            onPointerDown={(event) => event.preventDefault()}
            onClick={onRetry}
          >
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
          <RouteItems selectedFrom="searchResults" routes={view.routes} onSelect={onSelect} />
        </section>
      );
  }
}

function InServiceSection({ routes, onSelect }: { routes: RouteSummary[]; onSelect: SearchResultsProps["onSelect"] }) {
  const titleId = useId();

  return (
    <section className={styles.sheet} aria-labelledby={titleId}>
      <h2 id={titleId} className={styles.sectionTitle}>
        {IN_SERVICE_TITLE}
      </h2>
      <RouteItems selectedFrom="inService" routes={routes} onSelect={onSelect} />
    </section>
  );
}

interface RouteItemsProps {
  selectedFrom: SelectedFrom;
  routes: RouteSummary[];
  onSelect: SearchResultsProps["onSelect"];
}

function RouteItems({ selectedFrom, routes, onSelect }: RouteItemsProps) {
  return (
    <ul className={styles.list}>
      {routes.map((route, position) => (
        <li key={route.id}>
          <button
            className={styles.item}
            type="button"
            aria-label={routeButtonLabel(route)}
            onClick={() => onSelect({ route, selectedFrom, position, listedCount: routes.length })}
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
  );
}
