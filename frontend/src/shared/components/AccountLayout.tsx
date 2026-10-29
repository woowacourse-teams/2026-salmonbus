import type { ReactNode } from "react";
import * as styles from "./AccountLayout.css";

interface AccountLayoutProps {
  children: ReactNode;
  contentClassName?: string;
}

export function AccountLayout({ children, contentClassName }: AccountLayoutProps) {
  return (
    <div className={styles.shell}>
      <div className={styles.top} />
      <main className={`${styles.content} ${contentClassName ?? ""}`}>{children}</main>
    </div>
  );
}
