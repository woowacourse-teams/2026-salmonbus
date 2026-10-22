import { Link, useLocation, useNavigate } from "react-router";
import { track } from "@/shared/analytics/track";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { boardPathFor, paths } from "@/shared/routing/paths";
import { routeSelectedPropertiesOf, unmatchedQueryOf } from "./analytics/searchEventPolicy";
import { useUnmatchedSearchEvent } from "./analytics/useUnmatchedSearchEvent";
import { SearchForm } from "./components/SearchForm";
import { SearchResults } from "./components/SearchResults";
import { submitTargetFor } from "./displayPolicy";
import { BACK_LABEL } from "./routeSearchCopy";
import { useRouteSearch } from "./useRouteSearch";
import * as styles from "./RouteSearchPage.css";

export function RouteSearchPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const { input, view, changeInput, retry } = useRouteSearch();
  useUnmatchedSearchEvent(unmatchedQueryOf(view, input));

  // 판정 보드에서 뒤로 가면 검색 화면을 거치지 않고 홈으로 간다.
  // 검색 화면이 첫 기록이면 그 자리를 홈으로 바꾼 뒤 보드를 쌓아서, 보드 앞에 홈이 있게 한다.
  const handleSelect = (route: RouteSummary, position: number) => {
    if (view.kind !== "matched") return;
    track("route_selected", routeSelectedPropertiesOf(route, position, view.routes.length));
    if (location.key === "default") {
      void navigate(paths.routeSelect, { replace: true });
      void navigate(boardPathFor(route.id));
      return;
    }
    void navigate(boardPathFor(route.id), { replace: true });
  };

  const handleSubmit = () => {
    const route = submitTargetFor(view);
    if (route) handleSelect(route, 0);
  };

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <BackLink />
        <SearchForm
          value={input}
          autoFocus
          onChange={changeInput}
          onClear={() => changeInput("")}
          onSubmit={handleSubmit}
        />
      </header>
      <main className={styles.results}>
        <SearchResults view={view} onSelect={handleSelect} onRetry={retry} />
      </main>
    </div>
  );
}

function BackLink() {
  const location = useLocation();
  const navigate = useNavigate();
  const isFirstPage = location.key === "default";

  return (
    <Link
      to={paths.routeSelect}
      onClick={(event) => {
        if (isFirstPage || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        void navigate(-1);
      }}
      className={styles.back}
      aria-label={BACK_LABEL}
    >
      <svg className={styles.backIcon} viewBox="0 0 24 24" aria-hidden="true">
        <path d="m15 5-7 7 7 7" />
      </svg>
    </Link>
  );
}
