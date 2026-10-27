import { useId } from "react";
import type { ListedRoute, RouteSelection, SearchView } from "../displayPolicy";
import {
  EMPTY_DESCRIPTION,
  EMPTY_TITLE,
  IN_SERVICE_TITLE,
  LOAD_FAILED_NOTICE,
  LOADING_NOTICE,
  NEW_BADGE,
  RETRY_LABEL,
  UNMATCHED_NOTICE,
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
    case "empty":
      return (
        <div className={styles.emptyGuide}>
          <svg className={styles.emptyIcon} viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <h2 className={styles.emptyTitle}>{EMPTY_TITLE}</h2>
          <p className={styles.emptyDescription}>{EMPTY_DESCRIPTION}</p>
        </div>
      );
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
          {/* 누르는 순간 검색창 포커스가 빠지면 키패드가 내려가면서 버튼이 움직이고 클릭이 취소된다 */}
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
      return <InServiceSection routes={view.routes} onSelect={onSelect} />;
  }
}

function InServiceSection({ routes, onSelect }: { routes: ListedRoute[]; onSelect: SearchResultsProps["onSelect"] }) {
  const titleId = useId();

  return (
    <section className={styles.section} aria-labelledby={titleId}>
      <h2 id={titleId} className={styles.sectionTitle}>
        {IN_SERVICE_TITLE}
      </h2>
      <ul className={styles.list}>
        {routes.map((route, position) => (
          <li key={route.id}>
            <button
              className={styles.item}
              type="button"
              aria-label={routeButtonLabel(route)}
              onClick={() => onSelect({ route, position, listedCount: routes.length })}
            >
              <span className={styles.routeNumber}>{route.displayName}</span>
              <span className={styles.stops}>
                {route.startStopName} ↔ {route.endStopName}
              </span>
              {route.isNew && <span className={styles.newBadge}>{NEW_BADGE}</span>}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
