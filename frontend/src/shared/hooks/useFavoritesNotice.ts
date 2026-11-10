import type { PolledResource } from "@/shared/api/polledResource";
import type { PolledRequestState } from "@/shared/api/usePolledRequest";
import { favoriteErrorOf } from "@/shared/utils/favoritePolicy";
import { useAuth } from "./useAuth";

export type FavoritesNoticeState =
  | { status: "hidden" }
  | { status: "pending"; message: string }
  | { status: "failure"; message: string; retrying: boolean; retry: () => void };

export function useFavoritesNotice(
  resource: PolledResource<unknown>,
  requestState: PolledRequestState,
  onRetry: () => void,
): FavoritesNoticeState {
  const { reload } = useAuth();
  if (resource.status === "pending") return { status: "pending", message: "즐겨찾기를 불러오고 있어요…" };
  if (resource.status === "idle" || resource.status === "success") return { status: "hidden" };
  const retrying = requestState === "loading";
  return {
    status: "failure",
    message: favoriteErrorOf(resource.failure, resource.status === "refetchError" ? "refresh" : "load"),
    retrying,
    retry: () => {
      if (retrying) return;
      if ("status" in resource.failure && resource.failure.status === 401) void reload();
      onRetry();
    },
  };
}
