import { useLocation, useNavigate } from "react-router";
import { useAuth } from "@/shared/hooks/useAuth";
import { Brand } from "@/shared/components/Brand";
import { BottomNavigation } from "@/shared/components/BottomNavigation";
import { LoginButton } from "@/shared/components/LoginButton";
import { Notice } from "@/shared/components/Notice";
import { HomeFavorites } from "@/features/favorites/HomeFavorites";
import { track } from "@/shared/analytics/track";
import type { RouteSummary } from "@/shared/api/routeForecast.types";
import { boardPathFor } from "@/shared/routing/paths";
import { routeSelectedPropertiesOf, unmatchedQueryOf } from "./analytics/searchEventPolicy";
import { useUnmatchedSearchEvent } from "./analytics/useUnmatchedSearchEvent";
import { SearchForm } from "./components/SearchForm";
import { SearchResults } from "./components/SearchResults";
import { submitTargetFor } from "./displayPolicy";
import { useRouteSearch } from "./useRouteSearch";
import * as styles from "./RouteSelectPage.css";

export function RouteSelectPage() {
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
      <main>
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
              <HomeFavorites key={auth.member.id} />
            ) : auth.status === "loading" ? (
              <p>로그인 상태를 확인하고 있어요…</p>
            ) : (
              <section className={styles.guestCard} aria-labelledby="guest-favorites-title">
                <span className={styles.guestIcon} aria-hidden="true" />
                <h2 id="guest-favorites-title" className={styles.guestTitle}>
                  내 정류장을 저장해 보세요
                </h2>
                <p className={styles.guestDescription}>
                  로그인을 하면 자주 타는 노선과 정류장을
                  <br />내 그룹에 모아 볼 수 있어요.
                </p>
                <LoginButton returnTo="/" className={styles.guestLogin} />
              </section>
            )}
          </section>
        )}
      </main>
      <BottomNavigation />
      {typeof notice === "string" && <Notice key={notice} message={notice} />}
    </div>
  );
}
