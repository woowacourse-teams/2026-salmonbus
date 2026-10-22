import { useEffect, useRef, useState, type ReactNode } from "react";
import { Link, NavLink } from "react-router";
import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import * as styles from "./account.css";

export function Brand() {
  return (
    <span className={styles.brand}>
      <img className={styles.mascot} src={salmong} alt="연어 버스 로고" />
      <span>Salmonbus</span>
    </span>
  );
}

export function BottomNavigation() {
  return (
    <nav className={styles.navigation} aria-label="주요 메뉴">
      <NavLink to="/" end className={styles.tab}>
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
        >
          <path d="m3 10 9-7 9 7v10H3Z M9 20v-7h6v7" />
        </svg>
        홈
      </NavLink>
      <NavLink to="/mypage" className={styles.tab}>
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.5"
          aria-hidden="true"
        >
          <circle cx="12" cy="7" r="3.5" />
          <path d="M5 21v-2a7 7 0 0 1 14 0v2" />
        </svg>
        마이페이지
      </NavLink>
    </nav>
  );
}

export function AccountLayout({ children }: { children: ReactNode }) {
  return (
    <div className={styles.shell}>
      <div className={styles.top} />
      <main className={styles.content}>{children}</main>
      <BottomNavigation />
    </div>
  );
}

export function LoginButton({ returnTo = "/" }: { returnTo?: string }) {
  return (
    <Link className={styles.primary} to={`/login?${new URLSearchParams({ returnTo })}`}>
      로그인 / 회원가입
    </Link>
  );
}

export function Notice({ message }: { message: string }) {
  const [dismissed, setDismissed] = useState(false);
  useEffect(() => {
    const timer = window.setTimeout(() => setDismissed(true), 4_000);
    return () => window.clearTimeout(timer);
  }, []);
  return dismissed ? null : (
    <div className={styles.toast} role="status">
      {message}
    </div>
  );
}

export function ConfirmDialog({
  title,
  children,
  onCancel,
}: {
  title: string;
  children: ReactNode;
  onCancel: () => void;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const previouslyFocused = document.activeElement;
    dialog?.showModal();
    return () => {
      dialog?.close();
      if (previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected) previouslyFocused.focus();
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={styles.dialog}
      aria-labelledby="account-dialog-title"
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
    >
      <h2 id="account-dialog-title" className={styles.dialogTitle}>
        {title}
      </h2>
      {children}
    </dialog>
  );
}
