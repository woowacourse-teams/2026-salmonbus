import { useAuth } from "@/shared/hooks/useAuth";
import type { PolledResource } from "@/shared/api/polledResource";
import { favoriteErrorOf } from "./favoritePolicy";
import * as styles from "./favorites.css";

export function FavoritesNotice({ resource, onRetry }: { resource: PolledResource<unknown>; onRetry: () => void }) {
  const { reload } = useAuth();
  if (resource.status === "pending")
    return (
      <p className={styles.subtle} role="status">
        즐겨찾기를 불러오고 있어요…
      </p>
    );
  if (resource.status === "success") return null;
  return (
    <div>
      <p className={styles.error} role="alert">
        {favoriteErrorOf(resource.failure)}
      </p>
      <button
        type="button"
        className={styles.action}
        onClick={() => {
          if ("status" in resource.failure && resource.failure.status === 401) void reload();
          onRetry();
        }}
      >
        다시 확인하기
      </button>
    </div>
  );
}
