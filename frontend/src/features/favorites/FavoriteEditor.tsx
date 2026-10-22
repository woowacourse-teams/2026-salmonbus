import { useEffect, useRef, useState } from "react";
import { deleteFavorite, saveFavoriteOrder, saveGroupOrder } from "@/shared/api/favorites.api";
import type { FavoriteGroup, SavedFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { useFavorites, useHomeFavorites } from "./FavoritesContext";
import { FavoritesNotice } from "./FavoritesNotice";
import { SortableList } from "./SortableList";
import { moveItem } from "./favoritePolicy";
import { useFavoriteAction } from "./useFavoriteAction";
import trashIcon from "./trash.svg";
import * as styles from "./FavoriteEditor.css";
import * as common from "./favorites.css";

export function FavoriteEditor({
  onClose,
  initialGroupId,
}: {
  onClose: (saved: boolean) => void;
  initialGroupId?: string | undefined;
}) {
  const { groups, reload } = useFavorites();
  const [saved, setSaved] = useState(groups);
  const [draft, setDraft] = useState(groups);
  const [selected, select] = useState(initialGroupId ?? groups[0]?.id);
  const group = draft.find((candidate) => candidate.id === selected) ?? draft[0];
  const [deleting, setDeleting] = useState<SavedFavorite | null>(null);
  const [partial, setPartial] = useState(false);
  const [revision, retry] = useState(0);
  const summaries = useHomeFavorites("all", revision);
  const favorites = latestSuccessOf(summaries)?.body.groups.flatMap((candidate) => candidate.favorites) ?? [];
  const { busy, error, setError, run } = useFavoriteAction();
  const editor = useRef<HTMLElement>(null);
  const doneButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    editor.current?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus();
  }, []);
  const idsOf = (items: { id: string }[]) => items.map((item) => item.id);
  const labelOf = (favorite: SavedFavorite) => {
    const item = favorites.find((candidate) => candidate.id === favorite.id);
    return `${item?.route.displayName || "노선 정보 없음"} · ${item?.stop.name || "정류장 정보 없음"}`;
  };
  const close = () => {
    if (busy) return;
    if (partial) reload();
    onClose(false);
  };
  async function remove() {
    if (!group || !deleting) return;
    if (await run(() => deleteFavorite(group.id, deleting.id))) {
      const withoutDeleted = (current: FavoriteGroup[]) =>
        current.map((candidate) =>
          candidate.id === group.id
            ? { ...candidate, favorites: candidate.favorites.filter((favorite) => favorite.id !== deleting.id) }
            : candidate,
        );
      setDraft(withoutDeleted);
      setSaved(withoutDeleted);
      setDeleting(null);
    }
  }
  async function save() {
    const changes: { groupId: string | null; ids: string[] }[] = [];
    if (JSON.stringify(idsOf(draft)) !== JSON.stringify(idsOf(saved)))
      changes.push({ groupId: null, ids: idsOf(draft) });
    for (const group of draft) {
      const before = saved.find((candidate) => candidate.id === group.id);
      if (JSON.stringify(group.favorites) !== JSON.stringify(before?.favorites))
        changes.push({ groupId: group.id, ids: idsOf(group.favorites) });
    }
    if (changes.length === 0) {
      onClose(true);
      return;
    }
    const ok = await run(async () => {
      let completed = 0;
      const persist = async (change: (typeof changes)[number]) => {
        const result = await (change.groupId === null
          ? saveGroupOrder(change.ids)
          : saveFavoriteOrder(change.groupId, change.ids));
        if (result.ok) {
          completed += 1;
          setSaved((current) =>
            change.groupId === null
              ? change.ids.map((id) => current.find((group) => group.id === id)!)
              : current.map((group) =>
                  group.id === change.groupId
                    ? {
                        ...group,
                        favorites: change.ids.map((id) => group.favorites.find((favorite) => favorite.id === id)!),
                      }
                    : group,
                ),
          );
        } else if (completed > 0) setPartial(true);
        return result;
      };
      let result = await persist(changes[0]!);
      for (const change of changes.slice(1)) {
        if (!result.ok) return result;
        result = await persist(change);
      }
      return result;
    });
    if (ok) onClose(true);
  }
  return (
    <section
      ref={editor}
      className={`${styles.editor} amp-mask`}
      aria-label="즐겨찾기 편집"
      onKeyDown={(event) => {
        if (event.key === "Escape" && deleting === null) close();
      }}
    >
      <h2 className={styles.srOnly}>즐겨찾기 편집</h2>
      <p id="favorite-sort-help" className={styles.srOnly}>
        손잡이를 끌거나 선택 후 그룹은 왼쪽·오른쪽, 정류장은 위·아래 방향키로 순서를 바꿀 수 있어요. Escape 키를 누르면
        취소해요.
      </p>
      <SortableList
        items={draft}
        horizontal
        disabled={busy}
        label={(candidate) => candidate.name}
        onMove={(from, to) => setDraft((current) => moveItem(current, from, to))}
        render={(candidate, handle) => (
          <>
            <button
              type="button"
              className={styles.groupName}
              aria-pressed={group?.id === candidate.id}
              disabled={busy}
              onClick={() => select(candidate.id)}
            >
              {candidate.name}
            </button>
            {handle}
          </>
        )}
      />
      <FavoritesNotice resource={summaries} onRetry={() => retry((value) => value + 1)} />
      {group && (
        <SortableList
          key={group.id}
          items={group.favorites}
          disabled={busy}
          label={labelOf}
          onMove={(from, to) =>
            setDraft((current) =>
              current.map((candidate) =>
                candidate.id === group.id
                  ? { ...candidate, favorites: moveItem(candidate.favorites, from, to) }
                  : candidate,
              ),
            )
          }
          render={(favorite, handle) => {
            const item = favorites.find((candidate) => candidate.id === favorite.id);
            return (
              <>
                <div className={styles.info}>
                  <div className={styles.cardHeading}>
                    <span className={styles.route}>{item?.route.displayName || "노선 정보 없음"}</span>
                    <span className={styles.stop}>{item?.stop.name || "정류장 정보 없음"}</span>
                  </div>
                  <span className={styles.direction}>
                    {item?.route.terminalStopName?.trim()
                      ? `${item.route.terminalStopName.trim()} 방면`
                      : favorite.direction === "UP"
                        ? "상행"
                        : "하행"}
                  </span>
                </div>
                <button
                  type="button"
                  className={styles.trash}
                  disabled={busy}
                  aria-label={`${item?.stop.name || `즐겨찾기 ${group.favorites.indexOf(favorite) + 1}`} 삭제`}
                  onClick={(event) => {
                    event.currentTarget.focus();
                    setError(null);
                    setDeleting(favorite);
                  }}
                >
                  <img src={trashIcon} width="18" height="18" alt="" draggable={false} />
                </button>
                {handle}
              </>
            );
          }}
        />
      )}
      {group?.favorites.length === 0 && <p className={common.subtle}>아직 등록한 즐겨찾기가 없어요.</p>}
      {error && deleting === null && (
        <p className={common.error} role="alert">
          {error}
        </p>
      )}
      {partial && (
        <p className={common.subtle} role="status">
          일부 순서는 저장됐어요. 아직 저장하지 못한 순서만 다시 저장할 수 있어요.
        </p>
      )}
      <button ref={doneButton} type="button" className={styles.done} disabled={busy} onClick={() => void save()}>
        {busy ? "저장 중…" : "완료"}
      </button>
      {partial && (
        <button type="button" className={common.action} disabled={busy} onClick={close}>
          닫기
        </button>
      )}
      {deleting && (
        <ConfirmDialog
          title="즐겨찾기를 삭제할까요?"
          className={styles.deleteDialog}
          fallbackFocus={doneButton}
          onCancel={() => {
            if (!busy) setDeleting(null);
          }}
        >
          <p className={styles.deleteDescription}>
            {group?.name} 그룹에서만 삭제돼요.
            <br />
            필요하면 언제든 다시 추가할 수 있어요.
          </p>
          {error && (
            <p className={common.error} role="alert">
              {error}
            </p>
          )}
          <div className={styles.deleteActions}>
            <button type="button" className={styles.cancel} disabled={busy} onClick={() => setDeleting(null)}>
              취소
            </button>
            <button type="button" className={styles.confirm} disabled={busy} onClick={() => void remove()}>
              {busy ? "삭제 중…" : "삭제하기"}
            </button>
          </div>
        </ConfirmDialog>
      )}
    </section>
  );
}
