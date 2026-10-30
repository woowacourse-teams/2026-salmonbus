import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import { useAuth } from "@/shared/hooks/useAuth";
import type { PolledResource } from "@/shared/api/polledResource";
import { favoriteErrorOf } from "@/shared/utils/favoritePolicy";
import * as styles from "./FavoritesNotice.css";

interface FavoritesNoticeProps {
  resource: PolledResource<unknown>;
  onRetry: () => void;
  home?: boolean;
}

export function FavoritesNotice({ resource, onRetry, home = false }: FavoritesNoticeProps) {
  const { reload } = useAuth();
  if (resource.status === "pending")
    return (
      <p className={formStyles.subtle} role="status">
        즐겨찾기를 불러오고 있어요…
      </p>
    );
  if (resource.status === "idle" || resource.status === "success") return null;
  const retry = () => {
    if ("status" in resource.failure && resource.failure.status === 401) void reload();
    onRetry();
  };
  if (home) {
    return (
      <div className={styles.homeError}>
        <svg
          width="34"
          height="34"
          viewBox="0 0 24 24"
          fill="none"
          stroke="#C6B9AB"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <circle cx="12" cy="12" r="10" />
          <path d="m15 9-6 6m0-6 6 6" />
        </svg>
        <p className={styles.homeErrorTitle} role="alert">
          즐겨찾기를 불러오지 못했어요
        </p>
        <p className={styles.homeErrorDescription}>잠시 후 다시 시도해 주세요.</p>
        <FavoritesRetryButton onRetry={retry}>다시 시도</FavoritesRetryButton>
      </div>
    );
  }
  return (
    <div>
      <p className={formStyles.error} role="alert">
        {favoriteErrorOf(resource.failure)}
      </p>
      <button type="button" className={buttons.text} onClick={retry}>
        다시 확인하기
      </button>
    </div>
  );
}

export function FavoritesRetryButton({ children, onRetry }: { children: string; onRetry: () => void }) {
  return (
    <button type="button" className={styles.homeRetry} onClick={onRetry}>
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="#D65B46"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M21 12a9 9 0 1 1-9-9 9.75 9.75 0 0 1 6.74 2.74L21 8m0-5v5h-5" />
      </svg>
      {children}
    </button>
  );
}
