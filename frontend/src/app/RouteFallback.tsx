import * as styles from "./RouteFallback.css";

const ROUTE_LOADING_MESSAGE = "화면을 불러오고 있어요…";

export function AppRouteFallback() {
  return (
    <div className={styles.app}>
      <p className={styles.message} role="status">
        {ROUTE_LOADING_MESSAGE}
      </p>
    </div>
  );
}
