import { useId, useRef, type Ref } from "react";
import type { ListedRoute, RouteSelection, SearchView } from "../displayPolicy";
import {
  EMPTY_DESCRIPTION,
  EMPTY_TITLE,
  IN_SERVICE_TITLE,
  LOAD_FAILED_NOTICE,
  LOADING_NOTICE,
  NEW_BADGE,
  RELOAD_LABEL,
  RETRY_LABEL,
  UNMATCHED_NOTICE,
  routeButtonLabel,
  searchStatusLabel,
} from "../routeSearchCopy";
import * as styles from "./SearchResults.css";
import { useNewBadgeHop } from "./useNewBadgeHop";

interface SearchResultsProps {
  view: SearchView;
  onSelect: (selection: RouteSelection) => void;
  onRetry: () => void;
  newBadgeHopKey: string | null;
}

export function SearchResults({ view, onSelect, onRetry, newBadgeHopKey }: SearchResultsProps) {
  // 목록이 결과 없음으로 바뀌었다 돌아와도 이미 뛴 입력을 기억하도록, 늘 마운트된 이 컴포넌트가 뛰기를 맡는다.
  const sectionRef = useRef<HTMLElement>(null);
  useNewBadgeHop(sectionRef, newBadgeHopKey);

  return (
    <>
      {/* 화면 낭독기는 이 문구로 결과 변화를 듣는다. 보이는 문구는 두 번 읽히지 않게 aria-hidden */}
      <p className={styles.status} role="status">
        {searchStatusLabel(view)}
      </p>
      {renderView(view, onSelect, onRetry, sectionRef)}
    </>
  );
}

function renderView(
  view: SearchView,
  onSelect: SearchResultsProps["onSelect"],
  onRetry: () => void,
  sectionRef: Ref<HTMLElement>,
) {
  switch (view.kind) {
    case "empty":
      return (
        <div className={styles.emptyNotice}>
          <div aria-hidden="true">
            <h2 className={styles.emptyTitle}>{EMPTY_TITLE}</h2>
            <p className={styles.emptyDescription}>{EMPTY_DESCRIPTION}</p>
          </div>
          <RetryButton label={RELOAD_LABEL} onRetry={onRetry} />
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
          <RetryButton label={RETRY_LABEL} onRetry={onRetry} />
        </div>
      );
    case "unmatched":
      return (
        <p className={styles.notice} aria-hidden="true">
          {UNMATCHED_NOTICE}
        </p>
      );
    case "all":
    case "matched":
      return <InServiceSection ref={sectionRef} routes={view.routes} onSelect={onSelect} />;
  }
}

function RetryButton({ label, onRetry }: { label: string; onRetry: () => void }) {
  // 누르는 순간 검색창 포커스가 빠지면 키패드가 내려가면서 버튼이 움직이고 클릭이 취소된다
  return (
    <button
      className={styles.retryButton}
      type="button"
      onPointerDown={(event) => event.preventDefault()}
      onClick={onRetry}
    >
      {label}
    </button>
  );
}

interface InServiceSectionProps {
  ref: Ref<HTMLElement>;
  routes: ListedRoute[];
  onSelect: SearchResultsProps["onSelect"];
}

function InServiceSection({ ref, routes, onSelect }: InServiceSectionProps) {
  const titleId = useId();

  return (
    <section ref={ref} className={styles.section} aria-labelledby={titleId}>
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
              {route.isNew && (
                <span className={styles.newBadge} data-new-badge>
                  {NEW_BADGE}
                </span>
              )}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
