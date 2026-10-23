import { useLocation, useNavigate } from "react-router";
import { useAuth } from "@/shared/hooks/useAuth";
import { Brand } from "@/shared/components/Brand";
import { BottomNavigation } from "@/shared/components/BottomNavigation";
import { GuestFavoritesCard } from "./GuestFavoritesCard";
import { Notice } from "@/shared/components/Notice";
import { HomeFavorites } from "./HomeFavorites";
import { track } from "@/shared/analytics/track";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { boardPathFor } from "@/shared/routing/paths";
import { routeSelectedPropertiesOf, unmatchedQueryOf } from "../analytics/searchEventPolicy";
import { useUnmatchedSearchEvent } from "../analytics/useUnmatchedSearchEvent";
import { SearchForm } from "./SearchForm";
import { SearchResults } from "./SearchResults";
import { submitTargetFor } from "./displayPolicy";
import { useRouteSearch } from "../hooks/useRouteSearch";
import * as styles from "./RouteSelectScreen.css";
import { pageEnter } from "@/shared/styles/pageTransition.css";

export function RouteSelectScreen() {
  const navigate = useNavigate();
  const location = useLocation();
  const { state: auth } = useAuth();
  const notice: unknown = location.state?.notice;
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
      <main className={pageEnter}>
        <header className={styles.header}>
          <h1 style={{ margin: 0 }}>
            <Brand
              onClick={(event) => {
                if (event.button === 0 && !event.metaKey && !event.ctrlKey && !event.shiftKey && !event.altKey)
                  changeInput("");
              }}
            />
          </h1>
          <div className={styles.searchArea}>
            <SearchForm value={input} onChange={changeInput} onClear={() => changeInput("")} onSubmit={handleSubmit} />
            <SearchResults view={view} onSelect={handleSelect} onRetry={retry} />
          </div>
        </header>
        {input === "" && (
          <section className={styles.account} aria-label="회원 안내">
            {auth.status === "authenticated" ? (
              <HomeFavorites key={`${auth.member.id}:${location.key}`} />
            ) : auth.status === "loading" ? (
              <p>로그인 상태를 확인하고 있어요…</p>
            ) : (
              <GuestFavoritesCard />
            )}
          </section>
        )}
      </main>
      <BottomNavigation />
      {typeof notice === "string" && <Notice key={notice} message={notice} />}
    </div>
  );
}
