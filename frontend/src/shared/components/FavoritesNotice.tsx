import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import { useAuth } from "@/shared/hooks/useAuth";
import type { PolledResource } from "@/shared/api/polledResource";
import { favoriteErrorOf } from "@/shared/utils/favoritePolicy";

interface FavoritesNoticeProps {
  resource: PolledResource<unknown>;
  onRetry: () => void;
}

export function FavoritesNotice({ resource, onRetry }: FavoritesNoticeProps) {
  const { reload } = useAuth();
  if (resource.status === "pending")
    return (
      <p className={formStyles.subtle} role="status">
        즐겨찾기를 불러오고 있어요…
      </p>
    );
  if (resource.status === "success") return null;
  const retry = () => {
    if ("status" in resource.failure && resource.failure.status === 401) void reload();
    onRetry();
  };
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
