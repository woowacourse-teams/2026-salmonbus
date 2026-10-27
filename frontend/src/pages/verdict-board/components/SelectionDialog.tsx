import { useState, type SubmitEvent } from "react";
import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { createFavoriteGroup } from "@/shared/api/favorites.api";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import {
  MAX_FAVORITE_GROUPS,
  MAX_FAVORITES_PER_GROUP,
  exceedsFavoriteLimit,
  groupNameError,
  selectedGroups,
} from "@/shared/utils/favoritePolicy";
import plusIcon from "@/shared/assets/icons/favorite-group-plus.svg";
import { useSaveFavoriteSelections } from "../hooks/useSaveFavoriteSelections";
import * as styles from "./SelectionDialog.css";

interface SelectionDialogProps {
  target: FavoriteTarget;
  onClose: () => void;
  onSaved: () => void;
}

export function SelectionDialog({ target, onClose, onSaved }: SelectionDialogProps) {
  const { groups, resource, reload } = useFavorites();
  const [chosen, setChosen] = useState<string[] | null>(null);
  const ids = chosen ?? selectedGroups(groups, target);
  const { run, busy, error, setError } = useFavoriteAction();
  const saveSelections = useSaveFavoriteSelections(target);
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
    if (busy || invalidName || groups.length >= MAX_FAVORITE_GROUPS || resource.status !== "success") return;
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
          <span className={styles.choiceCount}>
            {group.favorites.length} / {MAX_FAVORITES_PER_GROUP}
          </span>
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
              disabled={
                busy || invalidName !== null || groups.length >= MAX_FAVORITE_GROUPS || resource.status !== "success"
              }
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
        groups.length < MAX_FAVORITE_GROUPS && (
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
              : `한 그룹에는 즐겨찾기를 ${MAX_FAVORITES_PER_GROUP}개까지 저장할 수 있어요.`)}
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
              void run(() => saveSelections(ids)).then((ok) => {
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
