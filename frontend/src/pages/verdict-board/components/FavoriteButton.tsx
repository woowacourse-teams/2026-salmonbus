import { useState } from "react";
import { track } from "@/shared/analytics/track";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { Notice } from "@/shared/components/Notice";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { selectedGroups } from "@/shared/utils/favoritePolicy";
import starOutline from "../assets/star-outline.svg";
import starFilled from "../assets/star-filled.svg";
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
  const { auth, groups, reload } = useFavorites();
  const [open, setOpen] = useState<"new" | "edit" | null>(null);
  const [notice, setNotice] = useState({ id: 0, message: "" });
  const registered = selectedGroups(groups, target).length > 0;
  return (
    <>
      <button
        type="button"
        className={`${styles.star} ${className}`}
        aria-label={`${stopName} 즐겨찾기`}
        aria-pressed={registered}
        onClick={(event) => {
          event.currentTarget.focus({ preventScroll: true });
          track("favorite_button_clicked", {
            route_id: target.routeId,
            direction: target.direction,
            stop_id: target.stopId,
            is_logged_in: auth.status === "authenticated",
            is_registered: registered,
          });
          reload();
          setOpen(registered ? "edit" : "new");
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
              setNotice((value) => ({
                id: value.id + 1,
                message: open === "edit" ? "변경사항을 저장했습니다" : "즐겨찾기를 저장했습니다",
              }));
            }}
          />
        ) : (
          <LoginPrompt target={target} onClose={() => setOpen(null)} />
        ))}
      {notice.id > 0 && <Notice key={notice.id} message={notice.message} />}
    </>
  );
}
