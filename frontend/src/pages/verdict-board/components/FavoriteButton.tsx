import * as formStyles from "@/shared/styles/form.css";
import { useState } from "react";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { saveFavoriteSelections } from "@/shared/api/favorites.api";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { LoginButton } from "@/shared/components/LoginButton";
import { Notice } from "@/shared/components/Notice";
import { useAuth } from "@/shared/hooks/useAuth";
import * as buttons from "@/shared/styles/button.css";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { exceedsFavoriteLimit, favoritePath, selectedGroups } from "@/shared/utils/favoritePolicy";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import starOutline from "../assets/star-outline.svg";
import starFilled from "../assets/star-filled.svg";
import closeIcon from "../assets/login-close.svg";
import * as promptStyles from "./LoginPrompt.css";
import * as styles from "./FavoriteButton.css";

export type FavoriteBoardRoute = Pick<FavoriteTarget, "routeId" | "direction"> & { directionName: string };

interface FavoriteButtonProps {
  className: string;
  target: FavoriteTarget;
  stopName: string;
  directionName: string;
}

export function FavoriteButton({ className, target, stopName, directionName }: FavoriteButtonProps) {
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
            stopName={stopName}
            directionName={directionName}
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

interface LoginPromptProps {
  target: FavoriteTarget;
  onClose: () => void;
}

function LoginPrompt({ target, onClose }: LoginPromptProps) {
  const { state, reload } = useAuth();
  return (
    <ConfirmDialog
      title={"로그인하면 즐겨찾기를\n등록할 수 있어요"}
      className={promptStyles.dialog}
      titleClassName={promptStyles.title}
      onCancel={onClose}
    >
      <button className={promptStyles.close} type="button" aria-label="닫기" onClick={onClose}>
        <img src={closeIcon} width="17" height="17" alt="" draggable={false} />
      </button>
      <p className={promptStyles.description}>
        로그인을 하면 자주 타는 노선과 정류장을
        <br />내 그룹에 모아 볼 수 있어요.
      </p>
      {state.status === "anonymous" ? (
        <LoginButton returnTo={favoritePath(target)} className={promptStyles.login} />
      ) : (
        <>
          <p className={formStyles.subtle} role="status">
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
    </ConfirmDialog>
  );
}

interface SelectionDialogProps {
  target: FavoriteTarget;
  stopName: string;
  directionName: string;
  onClose: () => void;
  onSaved: () => void;
}

function SelectionDialog({ target, stopName, directionName, onClose, onSaved }: SelectionDialogProps) {
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
      <p className={formStyles.subtle}>
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
        <p className={formStyles.subtle}>그룹을 준비하고 있어요. 목록을 다시 확인해 주세요.</p>
      )}
      {(error || full || missing) && (
        <p className={formStyles.error} role="alert">
          {error ??
            (missing
              ? "그룹 목록이 변경됐어요. 다시 확인해 주세요."
              : "한 그룹에는 즐겨찾기를 2개까지 저장할 수 있어요.")}
        </p>
      )}
      {missing && (
        <button
          className={buttons.text}
          type="button"
          onClick={() => {
            setChosen(null);
            reload();
          }}
        >
          그룹 목록 다시 확인하기
        </button>
      )}
      <div className={formStyles.actions}>
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
