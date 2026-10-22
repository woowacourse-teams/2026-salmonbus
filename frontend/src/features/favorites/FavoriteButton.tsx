import { useState } from "react";
import { Link } from "react-router";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { saveFavoriteSelections } from "@/shared/api/favorites.api";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { Notice } from "@/shared/components/Notice";
import { useAuth } from "@/shared/hooks/useAuth";
import * as buttons from "@/shared/styles/button.css";
import { useFavorites } from "./FavoritesContext";
import { FavoritesNotice } from "./FavoritesNotice";
import { exceedsFavoriteLimit, favoritePath, selectedGroups } from "./favoritePolicy";
import { useFavoriteAction } from "./useFavoriteAction";
import starOutline from "./star-outline.svg";
import starFilled from "./star-filled.svg";
import * as styles from "./favorites.css";

export type FavoriteBoardRoute = Pick<FavoriteTarget, "routeId" | "direction"> & { directionName: string };

export function FavoriteButton({
  className,
  target,
  stopName,
  directionName,
}: {
  className: string;
  target: FavoriteTarget;
  stopName: string;
  directionName: string;
}) {
  const { auth, groups, reload } = useFavorites();
  const [open, setOpen] = useState(false);
  const [notice, setNotice] = useState(0);
  const registered = selectedGroups(groups, target).length > 0;
  return (
    <>
      <button
        type="button"
        className={`${styles.star} ${className}`}
        aria-label={`${stopName} 즐겨찾기`}
        aria-pressed={registered}
        onClick={() => {
          reload();
          setOpen(true);
        }}
      >
        <img src={registered ? starFilled : starOutline} width="19" height="19" alt="" draggable={false} />
      </button>
      {open &&
        (auth.status === "authenticated" ? (
          <SelectionDialog
            key={auth.member.id}
            target={target}
            stopName={stopName}
            directionName={directionName}
            onClose={() => setOpen(false)}
            onSaved={() => {
              setOpen(false);
              setNotice((value) => value + 1);
            }}
          />
        ) : (
          <LoginPrompt target={target} onClose={() => setOpen(false)} />
        ))}
      {notice > 0 && <Notice key={notice} message="즐겨찾기를 저장했어요" />}
    </>
  );
}

function LoginPrompt({ target, onClose }: { target: FavoriteTarget; onClose: () => void }) {
  const { state, reload } = useAuth();
  return (
    <ConfirmDialog title="로그인하면 즐겨찾기를 등록할 수 있어요" onCancel={onClose}>
      {state.status === "anonymous" ? (
        <Link className={buttons.primary} to={`/login?${new URLSearchParams({ returnTo: favoritePath(target) })}`}>
          로그인 / 회원가입
        </Link>
      ) : (
        <>
          <p className={styles.subtle} role="status">
            {state.status === "loading"
              ? "로그인 상태를 확인하고 있어요…"
              : state.status === "unavailable"
                ? "로그인 기능을 준비 중이에요."
                : "로그인 상태를 확인하지 못했어요."}
          </p>
          {state.status === "error" && (
            <button className={buttons.secondary} onClick={() => void reload()}>
              다시 확인하기
            </button>
          )}
        </>
      )}
      <button className={styles.action} type="button" onClick={onClose}>
        닫기
      </button>
    </ConfirmDialog>
  );
}

function SelectionDialog({
  target,
  stopName,
  directionName,
  onClose,
  onSaved,
}: {
  target: FavoriteTarget;
  stopName: string;
  directionName: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { groups, resource, reload } = useFavorites();
  const [chosen, setChosen] = useState<string[] | null>(null);
  const ids = chosen ?? selectedGroups(groups, target);
  const { run, busy, error } = useFavoriteAction();
  const full = exceedsFavoriteLimit(groups, target, ids);
  const missing = ids.some((id) => !groups.some((group) => group.id === id));
  return (
    <ConfirmDialog
      title="저장할 그룹 선택"
      onCancel={() => {
        if (!busy) onClose();
      }}
    >
      <p className={styles.subtle}>
        {stopName} · {directionName} 방면
      </p>
      <FavoritesNotice resource={resource} onRetry={reload} />
      {groups.map((group) => (
        <label className={`${styles.choice} amp-mask`} key={group.id}>
          <span>{group.name}</span>
          <span className={styles.choiceCount}>{group.favorites.length} / 2</span>
          <input
            type="checkbox"
            checked={ids.includes(group.id)}
            disabled={busy}
            onChange={(event) =>
              setChosen(event.target.checked ? [...ids, group.id] : ids.filter((id) => id !== group.id))
            }
          />
        </label>
      ))}
      {resource.status === "success" && groups.length === 0 && (
        <p className={styles.subtle}>그룹을 준비하고 있어요. 목록을 다시 확인해 주세요.</p>
      )}
      {(error || full || missing) && (
        <p className={styles.error} role="alert">
          {error ??
            (missing
              ? "그룹 목록이 변경됐어요. 다시 확인해 주세요."
              : "한 그룹에는 즐겨찾기를 2개까지 저장할 수 있어요.")}
        </p>
      )}
      {missing && (
        <button
          className={styles.action}
          type="button"
          onClick={() => {
            setChosen(null);
            reload();
          }}
        >
          그룹 목록 다시 확인하기
        </button>
      )}
      <div className={styles.actions}>
        <button
          className={buttons.primary}
          disabled={busy || resource.status !== "success" || groups.length === 0 || full || missing}
          onClick={() =>
            void run(() => saveFavoriteSelections(target, ids)).then((ok) => {
              if (ok) onSaved();
            })
          }
        >
          {busy ? "저장 중…" : "저장"}
        </button>
        <button className={buttons.secondary} disabled={busy} onClick={onClose}>
          취소
        </button>
      </div>
    </ConfirmDialog>
  );
}
