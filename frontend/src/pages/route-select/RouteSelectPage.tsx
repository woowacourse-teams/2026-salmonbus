import { Link } from "react-router";
import salmongProud from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import salmonbusWordmark from "@/shared/assets/images/salmonbus-wordmark.webp";
import { paths } from "@/shared/routing/paths";
import { SEARCH_ENTRY_LABEL, SEARCH_QUESTION } from "./routeSelectCopy";
import * as styles from "./RouteSelectPage.css";

export function RouteSelectPage() {
  return (
    <div className={styles.page}>
      <header className={styles.header}>
        <div className={styles.brandLockup}>
          <img className={styles.mascot} src={salmongProud} alt="연어 버스 로고" />
          <img className={styles.wordmark} src={salmonbusWordmark} width={175} height={45} alt="연어 버스 글자 로고" />
        </div>
      </header>
      <main>
        <div className={styles.searchArea}>
          <h1 className={styles.question}>{SEARCH_QUESTION}</h1>
          <Link className={styles.searchEntry} to={paths.routeSearch}>
            <span className={styles.searchEntryText}>{SEARCH_ENTRY_LABEL}</span>
            <svg className={styles.searchIcon} viewBox="0 0 24 24" aria-hidden="true">
              <circle cx="11" cy="11" r="7" />
              <path d="m20 20-3.5-3.5" />
            </svg>
          </Link>
        </div>
      </main>
    </div>
  );
}
