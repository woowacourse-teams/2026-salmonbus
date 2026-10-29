import { directionLabelOf } from "@/shared/utils/directionLabelOf";
import { useEffect, useRef, useState } from "react";
import { deleteFavorite, saveFavoriteOrder, saveGroupOrder } from "@/shared/api/favorites.api";
import type { FavoriteGroup, HomeFavorite, SavedFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { EmptyFavoriteGroup } from "@/shared/components/EmptyFavoriteGroup";
import { FavoriteGroupAddButton } from "@/shared/components/FavoriteGroupAddButton";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { useFavorites, useHomeFavorites } from "@/shared/hooks/useFavorites";
import { FavoritesNotice, PartialFavoriteOrderNotice } from "@/shared/components/FavoritesNotice";
import { SortableList } from "./SortableList";
import { moveItem } from "@/shared/utils/moveItem";
import { useFavoriteGroupDeletion } from "@/shared/hooks/useFavoriteGroupDeletion";
import trashIcon from "@/shared/assets/icons/trash.svg";
import * as styles from "./FavoriteEditor.css";
import * as common from "@/shared/styles/form.css";
import * as buttons from "@/shared/styles/button.css";

interface FavoriteEditorProps {
  onClose: (saved: boolean) => void;
  initialGroupId?: string;
  initialFavorites?: HomeFavorite[];
}

interface FavoriteOrderChange {
  groupId: string | null;
  ids: string[];
}

interface GroupDrag {
  overTarget: boolean;
}

export function FavoriteEditor({ onClose, initialGroupId, initialFavorites }: FavoriteEditorProps) {
  const { groups, reload } = useFavorites();
  const [saved, setSaved] = useState(groups);
  const [draft, setDraft] = useState(groups);
  const [selected, select] = useState(initialGroupId ?? groups[0]?.id);
  const group = draft.find((candidate) => candidate.id === selected) ?? draft[0];
  const [deleting, setDeleting] = useState<SavedFavorite | null>(null);
  const [deletingGroup, setDeletingGroup] = useState<FavoriteGroup | null>(null);
  const [groupDrag, setGroupDrag] = useState<GroupDrag | null>(null);
  const protectedGroup = deletingGroup !== null && draft[0]?.id === deletingGroup.id;
  const [revision, retry] = useState(0);
  const summaries = useHomeFavorites({ kind: "all" }, revision);
  const favorites =
    latestSuccessOf(summaries)?.body.groups.flatMap((candidate) => candidate.favorites) ?? initialFavorites ?? [];
  const hasGroupDetails =
    initialFavorites !== undefined &&
    group?.favorites.every((favorite) => favorites.some((item) => item.id === favorite.id));
  const {
    busy,
    error,
    setError,
    run,
    partial,
    setPartial,
    removeGroup: deleteGroup,
  } = useFavoriteGroupDeletion(saved, draft, setSaved);
  const editor = useRef<HTMLElement>(null);
  const doneButton = useRef<HTMLButtonElement>(null);
  const groupDropArea = useRef<HTMLDivElement>(null);
  useEffect(() => {
    editor.current?.querySelector<HTMLButtonElement>('[aria-pressed="true"]')?.focus();
  }, [group?.id]);
  const idsOf = (items: { id: string }[]) => items.map((item) => item.id);
  const labelOf = (favorite: SavedFavorite) => {
    const item = favorites.find((candidate) => candidate.id === favorite.id);
    return `${item?.route.displayName || "노선 정보 없음"} · ${item?.stop.name || "정류장 정보 없음"}`;
  };
  const close = () => {
    if (busy) return;
    if (partial || error) reload();
    onClose(false);
  };
  function addGroup(created: FavoriteGroup) {
    setSaved((current) => [...current, created]);
    setDraft((current) => [...current, created]);
    select(created.id);
  }
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
  async function removeGroup() {
    if (!deletingGroup || protectedGroup || busy) return;
    if (await deleteGroup(deletingGroup.id)) {
      setSaved((current) => current.filter((candidate) => candidate.id !== deletingGroup.id));
      setDraft((current) => current.filter((candidate) => candidate.id !== deletingGroup.id));
      setDeletingGroup(null);
    } else {
      reload();
    }
  }
  async function save() {
    const changes: FavoriteOrderChange[] = [];
    if (JSON.stringify(idsOf(draft)) !== JSON.stringify(idsOf(saved)))
      changes.push({ groupId: null, ids: idsOf(draft) });
    for (const group of draft) {
      const before = saved.find((candidate) => candidate.id === group.id);
      if (JSON.stringify(group.favorites) !== JSON.stringify(before?.favorites))
        changes.push({ groupId: group.id, ids: idsOf(group.favorites) });
    }
    const [first, ...remaining] = changes;
    if (first === undefined) {
      onClose(true);
      return;
    }
    const saveChanges = async () => {
      let completed = 0;
      const persist = async (change: FavoriteOrderChange) => {
        const result = await (change.groupId === null
          ? saveGroupOrder(change.ids)
          : saveFavoriteOrder(change.groupId, change.ids));
        if (result.ok) {
          completed += 1;
          setSaved((current) =>
            change.groupId === null
              ? [...current].sort((left, right) => change.ids.indexOf(left.id) - change.ids.indexOf(right.id))
              : current.map((group) =>
                  group.id === change.groupId
                    ? {
                        ...group,
                        favorites: [...group.favorites].sort(
                          (left, right) => change.ids.indexOf(left.id) - change.ids.indexOf(right.id),
                        ),
                      }
                    : group,
                ),
          );
        } else if (completed > 0) setPartial(true);
        return result;
      };
      let result = await persist(first);
      for (const change of remaining) {
        if (!result.ok) return result;
        result = await persist(change);
      }
      return result;
    };
    if (await run(saveChanges, { resultHaptic: true })) onClose(true);
  }
  return (
    <section
      ref={editor}
      {...maskedPropsFor(styles.editor)}
      aria-label="즐겨찾기 편집"
      aria-busy={summaries.status === "pending" && !hasGroupDetails}
      onKeyDown={(event) => {
        if (event.target instanceof Element && event.target.closest("dialog")) return;
        if (event.key === "Escape" && deleting === null && deletingGroup === null) close();
      }}
    >
      <h2 className={visuallyHidden}>즐겨찾기 편집</h2>
      <p id="favorite-sort-help" className={visuallyHidden}>
        손잡이를 끌거나 선택 후 그룹은 왼쪽·오른쪽, 정류장은 위·아래 방향키로 순서를 바꿀 수 있어요. Escape 키를 누르면
        취소해요. 그룹을 아래 휴지통에 놓으면 삭제를 확인할 수 있어요.
      </p>
      <div className={styles.groupControls}>
        <div className={styles.groupList}>
          <SortableList
            items={draft}
            horizontal
            disabled={busy}
            label={(candidate) => candidate.name}
            onMove={(from, to) => setDraft((current) => moveItem(current, from, to))}
            dropZone={{
              target: groupDropArea,
              onDrag: (dragged, overTarget) => setGroupDrag(dragged ? { overTarget } : null),
              onDrop: (dropped) => {
                setError(null);
                setDeletingGroup(dropped);
              },
            }}
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
        </div>
        <FavoriteGroupAddButton disabled={busy} onCreated={addGroup} />
      </div>
      {summaries.status === "pending" ? (
        !hasGroupDetails && (
          <p className={visuallyHidden} role="status">
            즐겨찾기를 불러오고 있어요…
          </p>
        )
      ) : (
        <FavoritesNotice resource={summaries} onRetry={() => retry((value) => value + 1)} />
      )}
      <div
        ref={groupDropArea}
        className={styles.groupDropArea}
        data-group-drop-zone=""
        data-dragging={groupDrag !== null}
        data-drop-active={groupDrag?.overTarget ?? false}
        role={groupDrag ? "region" : undefined}
        aria-label={groupDrag ? "그룹 삭제 영역" : undefined}
      >
        <div className={styles.dropContent} inert={groupDrag !== null}>
          {group && group.favorites.length > 0 && (
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
                      {summaries.status === "pending" && !item ? (
                        <>
                          <span className={styles.loadingName} aria-hidden="true" />
                          <span className={styles.loadingDirection} aria-hidden="true" />
                        </>
                      ) : (
                        <div className={styles.cardHeading}>
                          <RouteBadge
                            size={item?.route.displayName ? "large" : "small"}
                            className={item?.route.displayName ? styles.routeBadge : ""}
                          >
                            {item?.route.displayName || "노선 정보 없음"}
                          </RouteBadge>
                          <div className={styles.stopNameAndDirection}>
                            <span className={styles.stop}>{item?.stop.name || "정류장 정보 없음"}</span>
                            <span className={styles.direction}>
                              {directionLabelOf(item?.route.terminalStopName, favorite.direction)}
                            </span>
                          </div>
                        </div>
                      )}
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
          {group?.favorites.length === 0 && <EmptyFavoriteGroup />}
        </div>
        {groupDrag && (
          <div className={styles.dropHint}>
            <span className={styles.dropIcon} aria-hidden="true" />
            <span>그룹 삭제</span>
          </div>
        )}
      </div>
      {error && deleting === null && deletingGroup === null && (
        <p className={common.error} role="alert">
          {error}
        </p>
      )}
      {partial && deletingGroup === null && <PartialFavoriteOrderNotice />}
      <button
        ref={doneButton}
        type="button"
        className={styles.done}
        disabled={busy}
        aria-disabled={busy || groupDrag !== null}
        onClick={() => {
          if (groupDrag === null) void save();
        }}
      >
        {busy ? "저장 중…" : "완료"}
      </button>
      {(partial || error) && (
        <button type="button" className={buttons.text} disabled={busy} onClick={close}>
          닫기
        </button>
      )}
      {deletingGroup && (
        <ConfirmDialog
          title={protectedGroup ? "대표 그룹은 삭제할 수 없어요" : "그룹을 삭제하시겠습니까?"}
          className={styles.deleteDialog}
          titleClassName={styles.deleteTitle}
          fallbackFocus={doneButton}
          onCancel={() => {
            if (!busy) setDeletingGroup(null);
          }}
        >
          <p className={styles.deleteDescription}>
            {protectedGroup ? (
              "다른 그룹을 맨 위로 옮긴 뒤 삭제해 주세요."
            ) : (
              <>
                {deletingGroup.name} 그룹과 즐겨찾기 {deletingGroup.favorites.length}개가 함께 삭제돼요.
                <br />
                다른 그룹의 즐겨찾기는 유지돼요.
              </>
            )}
          </p>
          {error && (
            <p className={common.error} role="alert">
              {error}
            </p>
          )}
          {partial && <PartialFavoriteOrderNotice />}
          {protectedGroup ? (
            <button className={styles.cancel} onClick={() => setDeletingGroup(null)}>
              확인
            </button>
          ) : (
            <div className={styles.deleteActions}>
              <button className={styles.cancel} disabled={busy} onClick={() => setDeletingGroup(null)}>
                취소
              </button>
              <button className={styles.confirm} disabled={busy} onClick={() => void removeGroup()}>
                {busy ? "삭제 중…" : "삭제"}
              </button>
            </div>
          )}
        </ConfirmDialog>
      )}
      {deleting && (
        <ConfirmDialog
          title="즐겨찾기를 삭제할까요?"
          className={styles.deleteDialog}
          titleClassName={styles.deleteTitle}
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
