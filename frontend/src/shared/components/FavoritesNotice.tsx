import type { ReactNode } from "react";
import type { PolledResource } from "@/shared/api/polledResource";
import type { PolledRequestState } from "@/shared/api/usePolledRequest";
import { useFavoritesNotice, type FavoritesNoticeState } from "@/shared/hooks/useFavoritesNotice";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";

interface FavoritesNoticeProps {
  resource: PolledResource<unknown>;
  requestState: PolledRequestState;
  onRetry: () => void;
  children?: (notice: Extract<FavoritesNoticeState, { status: "failure" }>) => ReactNode;
}

export function FavoritesNotice({ resource, requestState, onRetry, children }: FavoritesNoticeProps) {
  const notice = useFavoritesNotice(resource, requestState, onRetry);
  if (notice.status === "hidden") return null;
  if (notice.status === "pending")
    return (
      <p className={formStyles.subtle} role="status">
        {notice.message}
      </p>
    );
  if (children) return children(notice);
  return (
    <div>
      <p className={formStyles.error} role="alert">
        {notice.message}
      </p>
      <button
        type="button"
        className={buttons.text}
        onClick={notice.retry}
        disabled={notice.retrying}
        aria-busy={notice.retrying}
      >
        {notice.retrying ? "불러오는 중…" : "다시 시도하기"}
      </button>
    </div>
  );
}

export function PartialFavoriteOrderNotice() {
  return (
    <p className={formStyles.subtle} role="status">
      일부 순서는 저장됐어요. 남은 작업을 다시 시도해 주세요.
    </p>
  );
}
