import { Link, useLocation } from "react-router";
import { useAuth } from "@/shared/hooks/useAuth";
import { BottomNavigation } from "@/shared/components/BottomNavigation";
import { Brand } from "@/shared/components/Brand";
import { LoginButton } from "@/shared/components/LoginButton";
import { Notice } from "@/shared/components/Notice";
import { useOpenNumericKeypad } from "@/shared/keypad/NumericKeypadProvider";
import { paths } from "@/shared/routing/paths";
import { SEARCH_ENTRY_LABEL } from "./routeSelectCopy";
import * as styles from "./RouteSelectPage.css";

export function RouteSelectPage() {
  const location = useLocation();
  const { state: auth } = useAuth();
  const notice: unknown = location.state?.notice;
  const openNumericKeypad = useOpenNumericKeypad();

  return (
    <div className={styles.page}>
      <main>
        <header className={styles.header}>
          <h1 style={{ margin: 0 }}>
            <Brand />
          </h1>
          <div className={styles.searchArea}>
            <Link
              className={styles.searchEntry}
              to={paths.routeSearch}
              onClick={(event) => {
                // 새 탭으로 여는 클릭은 이 화면에 머물러서, 키패드를 띄우면 넘겨받을 입력창이 없다.
                if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
                openNumericKeypad();
              }}
            >
              <span className={styles.searchEntryText}>{SEARCH_ENTRY_LABEL}</span>
              <svg className={styles.searchIcon} viewBox="0 0 24 24" aria-hidden="true">
                <circle cx="11" cy="11" r="7" />
                <path d="m20 20-3.5-3.5" />
              </svg>
            </Link>
          </div>
        </header>
        <section className={styles.account} aria-label="회원 안내">
          {auth.status === "authenticated" ? (
            <p className={styles.welcome}>어서오세요 연어버스입니다!</p>
          ) : auth.status === "loading" ? (
            <p>로그인 상태를 확인하고 있어요…</p>
          ) : (
            <LoginButton returnTo="/" />
          )}
        </section>
      </main>
      <BottomNavigation />
      {typeof notice === "string" && <Notice key={notice} message={notice} />}
    </div>
  );
}
