import salmongCrying from "../assets/images/salmong-logo/salmong-crying.png";
import * as styles from "./ErrorView.css";

interface ErrorViewActionLink {
  href: string;
  label: string;
}

interface ErrorViewProps {
  title: string;
  caption: string;
  onBack?: () => void;
  onRetry?: () => void;
  retryLabel?: string;
  actionLink?: ErrorViewActionLink;
}

export function ErrorView({
  title,
  caption,
  onBack,
  onRetry,
  retryLabel = "다시 시도하기",
  actionLink,
}: ErrorViewProps) {
  return (
    <div className={styles.page}>
      {onBack && (
        <header className={styles.header}>
          <button className={styles.back} type="button" onClick={onBack} aria-label="뒤로가기 버튼">
            ←
          </button>
        </header>
      )}
      <main className={styles.content}>
        <img className={styles.mascot} src={salmongCrying} alt="연어 버스 로고" width={96} height={96} />
        <h1 className={styles.title}>{title}</h1>
        <p className={styles.caption}>{caption}</p>
        {onRetry && (
          <button className={styles.retry} type="button" onClick={onRetry}>
            {retryLabel}
          </button>
        )}
        {actionLink && (
          <a className={styles.retry} href={actionLink.href}>
            {actionLink.label}
          </a>
        )}
      </main>
    </div>
  );
}
