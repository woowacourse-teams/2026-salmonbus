import { useEffect, useId, useRef, useState } from "react";
import { Link } from "react-router";
import type { FavoriteGroup } from "@/shared/api/favorites.types";
import { saveGroupOrder } from "@/shared/api/favorites.api";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { SortableList } from "@/shared/components/favorite-editor/SortableList";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { useFavoriteGroupDeletion } from "@/shared/hooks/useFavoriteGroupDeletion";
import { PartialFavoriteOrderNotice } from "@/shared/components/FavoritesNotice";
import { moveItem } from "@/shared/utils/moveItem";
import { MAX_FAVORITE_GROUPS, MAX_FAVORITES_PER_GROUP } from "@/shared/utils/favoritePolicy";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import * as buttons from "@/shared/styles/button.css";
import * as form from "@/shared/styles/form.css";
import * as styles from "./GroupList.css";
import plusIcon from "@/shared/assets/icons/favorite-group-plus.svg";

interface GroupOrderEditorProps {
  onClose: (saved: boolean) => void;
}

export function GroupOrderEditor({ onClose }: GroupOrderEditorProps) {
  const { groups, resource, reload } = useFavorites();
  const [saved, setSaved] = useState(groups);
  const [draft, setDraft] = useState(groups);
  const [deleting, setDeleting] = useState<FavoriteGroup | null>(null);
  const { busy, error, setError, run, partial, setPartial, removeGroup } = useFavoriteGroupDeletion(
    saved,
    draft,
    setSaved,
  );
  const labelIdPrefix = useId();
  const done = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    done.current?.focus();
  }, []);
  const protectedGroup = deleting !== null && draft[0]?.id === deleting.id;
  async function save() {
    if (busy) return;
    const ids = draft.map((group) => group.id);
    if (
      ids.every((id, index) => saved[index]?.id === id) ||
      (await run(() => saveGroupOrder(ids), { resultHaptic: true }))
    )
      onClose(true);
  }
  async function remove() {
    if (!deleting || protectedGroup || resource.status !== "success") return;
    if (await removeGroup(deleting.id)) {
      setPartial(false);
      setSaved((current) => current.filter((group) => group.id !== deleting.id));
      setDraft((current) => current.filter((group) => group.id !== deleting.id));
      setDeleting(null);
    } else reload();
  }
  return (
    <section
      {...maskedPropsFor(styles.section)}
      aria-label="즐겨찾기 그룹 편집"
      onKeyDown={(event) => {
        if (event.key === "Escape" && deleting === null && !busy) onClose(false);
      }}
    >
      <h2 className={styles.heading}>
        <span>{`내 그룹  ${draft.length} / ${MAX_FAVORITE_GROUPS}`}</span>
        <button ref={done} className={styles.cancelSelection} disabled={busy} onClick={() => void save()}>
          완료
        </button>
      </h2>
      <p id="favorite-sort-help" className={visuallyHidden}>
        손잡이를 끌거나 위·아래 방향키로 그룹 순서를 바꿀 수 있어요. Escape 키를 누르면 취소해요.
      </p>
      <SortableList
        ariaLabel="그룹 순서"
        itemClassName={styles.group}
        items={draft}
        disabled={busy}
        label={(group) => group.name}
        onMove={(from, to) => setDraft((current) => moveItem(current, from, to))}
        render={(group, handle) => (
          <>
            <span className={styles.info}>
              <span className={styles.name}>
                {group.name}
                {draft[0]?.id === group.id && " · 대표 그룹"}
              </span>
              <span className={styles.count}>
                {group.favorites.length} / {MAX_FAVORITES_PER_GROUP}
              </span>
            </span>
            <span className={styles.cardTools}>
              {draft[0]?.id !== group.id ? (
                <button
                  className={`${styles.cardDelete} ${styles.deleteTool}`}
                  aria-labelledby={`${labelIdPrefix}delete-${group.id}`}
                  disabled={busy || resource.status !== "success"}
                  onClick={(event) => {
                    event.currentTarget.focus();
                    setError(null);
                    setDeleting(group);
                  }}
                >
                  <span id={`${labelIdPrefix}delete-${group.id}`} hidden>
                    {`${group.name} 그룹 삭제`}
                  </span>
                </button>
              ) : (
                <span className={styles.deletePlaceholder} aria-hidden="true" />
              )}
              {handle}
            </span>
          </>
        )}
      />
      {error && deleting === null && (
        <p className={form.error} role="alert">
          {error}
        </p>
      )}
      {partial && deleting === null && <PartialFavoriteOrderNotice />}
      {draft.length < MAX_FAVORITE_GROUPS && (
        <Link className={styles.add} to="/mypage/groups/new">
          <img src={plusIcon} width="18" height="18" alt="" />새 그룹 만들기
        </Link>
      )}
      {deleting && (
        <ConfirmDialog
          title={`${deleting.name} 그룹을 삭제할까요?`}
          fallbackFocus={done}
          onCancel={() => {
            if (!busy) setDeleting(null);
          }}
        >
          <p className={form.subtle}>
            {protectedGroup
              ? "대표 그룹은 삭제할 수 없어요."
              : `이 그룹과 즐겨찾기 ${deleting.favorites.length}개가 함께 삭제돼요. 다른 그룹의 즐겨찾기는 유지돼요.`}
          </p>
          {error && (
            <p className={form.error} role="alert">
              {error}
            </p>
          )}
          {partial && <PartialFavoriteOrderNotice />}
          <div className={form.actions}>
            <button className={buttons.secondary} disabled={busy} onClick={() => setDeleting(null)}>
              취소
            </button>
            <button
              className={buttons.primary}
              disabled={busy || protectedGroup || resource.status !== "success"}
              onClick={() => void remove()}
            >
              {busy ? "삭제 중…" : "삭제"}
            </button>
          </div>
        </ConfirmDialog>
      )}
    </section>
  );
}
