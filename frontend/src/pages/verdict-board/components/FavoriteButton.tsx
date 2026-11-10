import { useState } from "react";
import { track } from "@/shared/analytics/track";
import { deleteFavorite } from "@/shared/api/favorites.api";
import type { FavoriteGroup, FavoriteTarget } from "@/shared/api/favorites.types";
import { Notice } from "@/shared/components/Notice";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { sameFavorite, selectedGroups } from "@/shared/utils/favoritePolicy";
import starOutline from "@/shared/assets/icons/star-outline.svg";
import starFilled from "../assets/star-filled.svg";
import { useSaveFavoriteSelections } from "../hooks/useSaveFavoriteSelections";
import { LoginPrompt } from "./LoginPrompt";
import { SelectionDialog } from "./SelectionDialog";
import * as styles from "./FavoriteButton.css";

export type FavoriteBoardRoute = Pick<FavoriteTarget, "routeId" | "direction">;

interface FavoriteButtonProps {
  className: string;
  target: FavoriteTarget;
  stopName: string;
}

export function FavoriteButton({ className, target, stopName }: FavoriteButtonProps) {
  const { auth, groups, resource, requestState, reload } = useFavorites();
  const { run, busy, error, clearError } = useFavoriteAction();
  const saveSelections = useSaveFavoriteSelections(target);
  const [open, setOpen] = useState<"new" | "edit" | null>(null);
  const [notice, setNotice] = useState({ id: 0, message: "" });
  const registered = selectedGroups(groups, target).length > 0;
  const waitingForGroups =
    auth.status === "authenticated" && (resource.status === "pending" || requestState === "loading");
  const unavailable = busy || waitingForGroups;
  const singleGroup =
    auth.status === "authenticated" && resource.status === "success" && groups.length === 1 ? groups[0] : undefined;

  function showNotice(message: string) {
    setNotice((value) => ({ id: value.id + 1, message }));
  }

  async function toggleFavorite(group: FavoriteGroup) {
    setNotice((value) => ({ ...value, message: "" }));
    const existing = group.favorites.find((favorite) => sameFavorite(favorite, target));
    const saved = await run(() => (existing ? deleteFavorite(group.id, existing.id) : saveSelections([group.id])), {
      kind: existing ? "deleteFavorite" : "saveFavorite",
      resultHaptic: true,
    });
    if (saved) showNotice(existing ? "즐겨찾기가 해제되었습니다" : "즐겨찾기에 추가되었습니다");
  }
  return (
    <>
      <button
        type="button"
        className={`${styles.star} ${className}`}
        aria-label={`${stopName} 즐겨찾기`}
        aria-pressed={registered}
        aria-disabled={unavailable || undefined}
        onClick={(event) => {
          if (unavailable) return;
          event.currentTarget.focus({ preventScroll: true });
          track("favorite_button_clicked", {
            route_id: target.routeId,
            direction: target.direction,
            stop_id: target.stopId,
            is_logged_in: auth.status === "authenticated",
            is_registered: registered,
          });
          clearError();
          if (singleGroup) {
            void toggleFavorite(singleGroup);
          } else {
            reload();
            setOpen(registered ? "edit" : "new");
          }
        }}
      >
        <img src={registered ? starFilled : starOutline} width="19" height="19" alt="" draggable={false} />
      </button>
      {open &&
        (auth.status === "authenticated" ? (
          <SelectionDialog
            key={auth.member.id}
            target={target}
            onClose={() => setOpen(null)}
            onSaved={() => {
              setOpen(null);
              showNotice(open === "edit" ? "변경사항을 저장했습니다" : "즐겨찾기를 저장했습니다");
            }}
          />
        ) : (
          <LoginPrompt target={target} onClose={() => setOpen(null)} />
        ))}
      {error ? (
        <Notice message={error} />
      ) : (
        notice.message !== "" && <Notice key={notice.id} message={notice.message} />
      )}
    </>
  );
}
