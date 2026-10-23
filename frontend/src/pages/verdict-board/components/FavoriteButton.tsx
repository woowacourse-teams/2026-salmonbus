import * as formStyles from "@/shared/styles/form.css";
import { useState, type SubmitEvent } from "react";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { createFavoriteGroup, saveFavoriteSelections } from "@/shared/api/favorites.api";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { LoginButton } from "@/shared/components/LoginButton";
import { Notice } from "@/shared/components/Notice";
import { useAuth } from "@/shared/hooks/useAuth";
import * as buttons from "@/shared/styles/button.css";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { exceedsFavoriteLimit, favoritePath, groupNameError, selectedGroups } from "@/shared/utils/favoritePolicy";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import starOutline from "../assets/star-outline.svg";
import starFilled from "../assets/star-filled.svg";
import closeIcon from "../assets/login-close.svg";
import plusIcon from "@/shared/assets/icons/favorite-group-plus.svg";
import * as promptStyles from "./LoginPrompt.css";
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
  onClose: () => void;
  onSaved: () => void;
}

function SelectionDialog({ target, onClose, onSaved }: SelectionDialogProps) {
  const { groups, resource, reload } = useFavorites();
  const [chosen, setChosen] = useState<string[] | null>(null);
  const ids = chosen ?? selectedGroups(groups, target);
  const { run, busy, error, setError } = useFavoriteAction();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [beforeCreation, setBeforeCreation] = useState<typeof resource | null>(null);
  // 생성 직전 응답이 남아 있는 동안 새 그룹을 누락으로 처리하지 않는다.
  const refreshingGroups = beforeCreation === resource;
  const invalidName = groupNameError(name);
  const full = exceedsFavoriteLimit(groups, target, ids);
  const missing = !refreshingGroups && ids.some((id) => !groups.some((group) => group.id === id));
  async function createGroup(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || invalidName || groups.length >= 3 || resource.status !== "success") return;
    await run(async () => {
      const result = await createFavoriteGroup(name);
      if (result.ok) {
        setBeforeCreation(resource);
        setChosen([...ids, result.body.id]);
        setName("");
        setCreating(false);
      }
      return result;
    });
  }
  return (
    <ConfirmDialog
      title="저장할 그룹 선택"
      onCancel={() => {
        if (!busy) onClose();
      }}
    >
      <FavoritesNotice resource={resource} onRetry={reload} />
      {refreshingGroups && (
        <p className={formStyles.subtle} role="status">
          그룹 목록을 갱신하고 있어요…
        </p>
      )}
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
      {creating ? (
        <form className={`${styles.createGroup} amp-mask`} onSubmit={(event) => void createGroup(event)}>
          <label htmlFor="favorite-group-name">그룹 이름</label>
          <input
            id="favorite-group-name"
            className={formStyles.field}
            value={name}
            autoFocus
            autoComplete="off"
            disabled={busy}
            aria-describedby="favorite-group-name-help favorite-group-name-error"
            aria-invalid={error !== null || (name !== "" && invalidName !== null)}
            onChange={(event) => {
              setName(event.target.value);
              setError(null);
            }}
          />
          <p id="favorite-group-name-help" className={formStyles.subtle}>
            앞뒤 공백을 제외한 1~10자, 특수문자와 이모지도 사용할 수 있어요.
          </p>
          <p id="favorite-group-name-error" className={formStyles.error} role="alert" hidden={!name || !invalidName}>
            {invalidName}
          </p>
          <div className={formStyles.actions}>
            <button
              className={buttons.primary}
              disabled={busy || invalidName !== null || groups.length >= 3 || resource.status !== "success"}
            >
              {busy ? "만드는 중…" : "만들기"}
            </button>
            <button
              className={buttons.secondary}
              type="button"
              disabled={busy}
              onClick={() => {
                setCreating(false);
                setName("");
                setError(null);
              }}
            >
              돌아가기
            </button>
          </div>
        </form>
      ) : (
        resource.status === "success" &&
        groups.length < 3 && (
          <button
            className={styles.addGroup}
            type="button"
            disabled={busy || refreshingGroups}
            onClick={() => {
              setError(null);
              setCreating(true);
            }}
          >
            <img src={plusIcon} width="18" height="18" alt="" draggable={false} />새 그룹 만들기
          </button>
        )
      )}
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
      {!creating && (
        <div className={formStyles.actions}>
          <button
            className={buttons.primary}
            disabled={
              busy || refreshingGroups || resource.status !== "success" || groups.length === 0 || full || missing
            }
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
      )}
    </ConfirmDialog>
  );
}
