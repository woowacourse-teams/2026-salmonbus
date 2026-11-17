import { AccountLayout } from "@/shared/components/AccountLayout";
import * as styles from "./RouteFallback.css";

export const ROUTE_LOADING_MESSAGE = "화면을 불러오고 있어요…";

export function AppRouteFallback() {
  return (
    <div className={styles.app}>
      <p className={styles.message} role="status">
        {ROUTE_LOADING_MESSAGE}
      </p>
    </div>
  );
}

export function PageRouteFallback({ message }: { message: string }) {
  return (
    <AccountLayout>
      <p className={styles.message} role="status">
        {message}
      </p>
    </AccountLayout>
  );
}

export function SheetRouteFallback() {
  return (
    <div className={styles.sheet}>
      <p className={styles.message} role="status">
        {ROUTE_LOADING_MESSAGE}
      </p>
    </div>
  );
}
