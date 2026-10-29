import { useState, type SubmitEvent } from "react";
import { createFavoriteGroup } from "@/shared/api/favorites.api";
import type { FavoriteGroup } from "@/shared/api/favorites.types";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { groupNameError, MAX_FAVORITE_GROUPS } from "@/shared/utils/favoritePolicy";
import plusIcon from "@/shared/assets/icons/favorite-group-plus.svg";
import * as buttons from "@/shared/styles/button.css";
import * as form from "@/shared/styles/form.css";
import * as styles from "./FavoriteGroupAddButton.css";

interface FavoriteGroupAddButtonProps {
  disabled?: boolean;
  onCreated?: (group: FavoriteGroup) => void;
}

export function FavoriteGroupAddButton({ disabled = false, onCreated }: FavoriteGroupAddButtonProps) {
  const { groups, resource } = useFavorites();
  const [creating, setCreating] = useState(false);
  const unavailable = disabled || resource.status !== "success";
  return (
    <>
      {groups.length < MAX_FAVORITE_GROUPS && (
        <button
          type="button"
          className={styles.add}
          aria-label="그룹 추가"
          title="그룹 추가"
          disabled={unavailable}
          onClick={(event) => {
            event.currentTarget.focus();
            setCreating(true);
          }}
        >
          <img src={plusIcon} width="18" height="18" alt="" draggable={false} />
        </button>
      )}
      {creating && <CreateGroupDialog onClose={() => setCreating(false)} {...(onCreated ? { onCreated } : {})} />}
    </>
  );
}

interface CreateGroupDialogProps {
  onClose: () => void;
  onCreated?: (group: FavoriteGroup) => void;
}

function CreateGroupDialog({ onClose, onCreated }: CreateGroupDialogProps) {
  const { groups, resource } = useFavorites();
  const [name, setName] = useState("");
  const { run, busy, error, setError } = useFavoriteAction();
  const invalid = groupNameError(name);
  const full = groups.length >= MAX_FAVORITE_GROUPS;
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy || invalid || full || resource.status !== "success") return;
    let created: FavoriteGroup | null = null;
    const ok = await run(
      async () => {
        const result = await createFavoriteGroup(name);
        if (result.ok) created = result.body;
        return result;
      },
      { resultHaptic: true },
    );
    if (ok && created !== null) {
      onCreated?.(created);
      onClose();
    }
  }
  return (
    <ConfirmDialog
      title="그룹 추가"
      onCancel={() => {
        if (!busy) onClose();
      }}
    >
      <form onSubmit={(event) => void submit(event)}>
        <input
          id="home-group-name"
          aria-label="그룹 이름"
          className={form.field}
          autoFocus
          autoComplete="off"
          value={name}
          aria-describedby="home-group-name-help home-group-name-error"
          aria-invalid={error !== null || (name !== "" && invalid !== null)}
          onChange={(event) => {
            setName(event.currentTarget.value);
            setError(null);
          }}
        />
        <p id="home-group-name-help" className={form.subtle}>
          앞뒤 공백을 제외한 1~10자, 특수문자와 이모지도 사용할 수 있어요.
        </p>
        <p
          id="home-group-name-error"
          className={form.error}
          role="alert"
          hidden={!error && !full && (!name || !invalid)}
        >
          {error ?? (full ? `그룹은 ${MAX_FAVORITE_GROUPS}개까지 만들 수 있어요.` : name ? invalid : null)}
        </p>
        <div className={form.actions}>
          <button type="button" className={buttons.secondary} disabled={busy} onClick={onClose}>
            취소
          </button>
          <button
            className={buttons.primary}
            disabled={busy || invalid !== null || full || resource.status !== "success"}
          >
            {busy ? "만드는 중…" : "만들기"}
          </button>
        </div>
      </form>
    </ConfirmDialog>
  );
}
