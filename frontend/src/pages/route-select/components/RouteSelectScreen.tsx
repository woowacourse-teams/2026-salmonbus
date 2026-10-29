import { useLocation } from "react-router";
import { useAuth } from "@/shared/hooks/useAuth";
import { BottomNavigation } from "@/shared/components/BottomNavigation";
import { Brand } from "@/shared/components/Brand";
import { GuestFavoritesCard } from "./GuestFavoritesCard";
import { HomeFavorites } from "./HomeFavorites";
import { WhatsNewOnEntry } from "./WhatsNewOnEntry";
import { pageEnter } from "@/shared/styles/pageTransition.css";
import { Notice } from "@/shared/components/Notice";
import { paths } from "@/shared/routing/paths";
import { useNavigateKeepingFocus } from "@/shared/routing/focusKeepingNavigation";
import { routeSearchStateFor } from "@/shared/routing/routeSearchEntry";
import { SEARCH_ENTRY_LABEL } from "../routeSelectCopy";
import * as styles from "./RouteSelectScreen.css";

export function RouteSelectScreen() {
  const location = useLocation();
  const { state: auth } = useAuth();
  const notice: unknown = location.state?.notice;
  const navigateKeepingFocus = useNavigateKeepingFocus();

  // 사용자가 진짜 입력창을 직접 눌러야 iOS가 숫자 키패드를 확실히 띄운다.
  // 검색 화면으로는 탭 처리가 끝난 click에서 넘어간다. 포커스 순간에 넘어가면 남은 탭 이벤트가 검색 화면에 떨어져 포커스를 빼앗는다.
  const openRouteSearch = () => navigateKeepingFocus(paths.routeSearch);
  const openRouteSearchWith = (input: string) => navigateKeepingFocus(paths.routeSearch, routeSearchStateFor(input));

  return (
    <div className={styles.page}>
      <main className={pageEnter}>
        <header className={styles.header}>
          <h1 style={{ margin: 0 }}>
            <Brand />
          </h1>
          <div className={styles.searchArea}>
            <label className={styles.searchEntry}>
              <input
                className={styles.searchEntryText}
                type="text"
                inputMode="numeric"
                autoComplete="off"
                placeholder={SEARCH_ENTRY_LABEL}
                aria-label={SEARCH_ENTRY_LABEL}
                onClick={openRouteSearch}
                onChange={(event) => openRouteSearchWith(event.currentTarget.value)}
                onKeyDown={(event) => {
                  if (event.key === "Enter") openRouteSearch();
                }}
              />
              <svg className={styles.searchIcon} viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="11" cy="11" r="7" />
                <path d="m20 20-3.5-3.5" />
              </svg>
            </label>
          </div>
        </header>
        <section className={styles.account} aria-label="회원 안내">
          {auth.status === "authenticated" ? (
            <HomeFavorites key={`${auth.member.id}:${location.key}`} />
          ) : auth.status === "loading" ? (
            <p>로그인 상태를 확인하고 있어요…</p>
          ) : (
            <GuestFavoritesCard />
          )}
        </section>
      </main>
      <BottomNavigation />
      {typeof notice === "string" && <Notice key={notice} message={notice} />}
      <WhatsNewOnEntry />
    </div>
  );
}
