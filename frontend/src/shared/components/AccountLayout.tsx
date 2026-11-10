import type { ReactNode } from "react";
import * as styles from "./AccountLayout.css";

interface AccountLayoutProps {
  children: ReactNode;
  contentClassName?: string;
  variant?: "tab";
}

export function AccountLayout({ children, contentClassName, variant }: AccountLayoutProps) {
  return (
    <div className={variant === "tab" ? styles.tabShell : styles.shell}>
      <div className={styles.top} />
      <main className={`${styles.content} ${contentClassName ?? ""}`}>{children}</main>
    </div>
  );
}
