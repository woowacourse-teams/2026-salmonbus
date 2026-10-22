import type { ReactNode } from "react";
import { BottomNavigation } from "./BottomNavigation";
import * as styles from "./AccountLayout.css";

export function AccountLayout({ children }: { children: ReactNode }) {
  return (
    <div className={styles.shell}>
      <div className={styles.top} />
      <main className={styles.content}>{children}</main>
      <BottomNavigation />
    </div>
  );
}
