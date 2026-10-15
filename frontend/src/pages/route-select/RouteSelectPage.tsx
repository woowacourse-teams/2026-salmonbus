import { useNavigate } from "react-router";
import { track } from "@/shared/analytics/track";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import salmongProud from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import salmonbusWordmark from "@/shared/assets/images/salmonbus-wordmark.webp";
import { boardPathFor } from "@/shared/routing/paths";
import { routeSelectedPropertiesOf, unmatchedQueryOf } from "./analytics/searchEventPolicy";
import { useUnmatchedSearchEvent } from "./analytics/useUnmatchedSearchEvent";
import { SearchForm } from "./components/SearchForm";
import { SearchResults } from "./components/SearchResults";
import { submitTargetFor } from "./displayPolicy";
import { SEARCH_QUESTION } from "./routeSelectCopy";
import { useRouteSearch } from "./useRouteSearch";
import * as styles from "./RouteSelectPage.css";

export function RouteSelectPage() {
  const navigate = useNavigate();
  const { input, view, changeInput, retry } = useRouteSearch();
  useUnmatchedSearchEvent(unmatchedQueryOf(view, input));

  const handleSelect = (route: RouteSummary, position: number) => {
    if (view.kind !== "matched") return;
    track("route_selected", routeSelectedPropertiesOf(route, position, view.routes.length));
    void navigate(boardPathFor(route.id));
  };

  const handleSubmit = () => {
    const route = submitTargetFor(view);
    if (route) handleSelect(route, 0);
  };

  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div className={styles.brandLockup}>
          <img className={styles.mascot} src={salmongProud} alt="연어 버스 로고" />
          <img className={styles.wordmark} src={salmonbusWordmark} width={175} height={45} alt="연어 버스 글자 로고" />
        </div>
      </header>
      <main>
        <div className={styles.searchArea} data-filled={input === "" ? undefined : ""}>
          <h1 className={styles.question}>{SEARCH_QUESTION}</h1>
          <SearchForm value={input} onChange={changeInput} onClear={() => changeInput("")} onSubmit={handleSubmit} />
          <SearchResults view={view} onSelect={handleSelect} onRetry={retry} />
        </div>
      </main>
    </div>
  );
}
