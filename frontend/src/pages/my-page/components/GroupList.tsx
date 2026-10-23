import { MAX_FAVORITE_GROUPS, MAX_FAVORITES_PER_GROUP } from "@/shared/utils/favoritePolicy";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { deleteFavoriteGroup } from "@/shared/api/favorites.api";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { FavoriteEditor } from "@/shared/components/favorite-editor/FavoriteEditor";
import { Notice } from "@/shared/components/Notice";
import * as formStyles from "@/shared/styles/form.css";
import * as styles from "./GroupList.css";
import editIcon from "@/shared/assets/icons/favorite-edit.svg";
import chevronIcon from "../assets/group-chevron.svg";
import plusIcon from "@/shared/assets/icons/favorite-group-plus.svg";

export function GroupList() {
  const { groups, resource, reload } = useFavorites();
  const [mode, setMode] = useState<"view" | "order" | "delete">("view");
  const [selected, setSelected] = useState<string[]>([]);
  const [confirming, setConfirming] = useState(false);
  const [deletedCount, setDeletedCount] = useState(0);
  const [notice, showNotice] = useState({ id: 0, message: "" });
  const { run, busy, error, setError } = useFavoriteAction();
  const editButton = useRef<HTMLButtonElement>(null);
  const deleteButton = useRef<HTMLButtonElement>(null);
  const selectionButton = useRef<HTMLButtonElement>(null);
  const deleting = mode === "delete";
  const targets = groups.slice(1).filter((group) => selected.includes(group.id));
  const changed = targets.length !== selected.length;
  useEffect(() => {
    if (mode === "view") return;
    const trigger = mode === "order" ? editButton : deleteButton;
    return () => trigger.current?.focus();
  }, [mode]);

  function finishDeletion(count: number) {
    setMode("view");
    setConfirming(false);
    setSelected([]);
    setError(null);
    if (count > 0) showNotice((value) => ({ id: value.id + 1, message: `그룹 ${count}개를 삭제했어요` }));
  }

  async function removeSelected() {
    const [first, ...rest] = targets;
    if (busy || changed || resource.status !== "success" || first === undefined) return;
    const removed: string[] = [];
    const ok = await run(async () => {
      let result = await deleteFavoriteGroup(first.id);
      if (result.ok) removed.push(first.id);
      for (const group of rest) {
        if (!result.ok) break;
        result = await deleteFavoriteGroup(group.id);
        if (result.ok) removed.push(group.id);
      }
      return result;
    });
    // 중간 실패로 이미 삭제된 그룹도 목록에서 갱신하고 다시 요청하지 않는다.
    if (!ok) reload();
    setSelected((current) => current.filter((id) => !removed.includes(id)));
    setDeletedCount((count) => count + removed.length);
    if (ok) finishDeletion(deletedCount + removed.length);
  }

  if (mode === "order")
    return (
      <div className={styles.section}>
        <FavoriteEditor
          onClose={(saved) => {
            setMode("view");
            if (saved) showNotice((value) => ({ id: value.id + 1, message: "변경 사항을 저장했어요" }));
          }}
        />
      </div>
    );
  return (
    <section className={`${styles.section} amp-mask`} aria-label="즐겨찾기 그룹 관리">
      <h2 className={styles.heading}>
        <span>{`내 그룹  ${groups.length} / ${MAX_FAVORITE_GROUPS}`}</span>
        {deleting ? (
          <button className={styles.cancelSelection} disabled={busy} onClick={() => finishDeletion(deletedCount)}>
            취소
          </button>
        ) : (
          <span className={styles.tools}>
            <button
              ref={deleteButton}
              className={`${styles.edit} ${styles.deleteTool}`}
              disabled={groups.length === 0 || resource.status !== "success"}
              aria-label="그룹 삭제 선택"
              onClick={(event) => {
                event.currentTarget.focus();
                setSelected([]);
                setDeletedCount(0);
                setError(null);
                setMode("delete");
                reload();
              }}
            />
            <button
              ref={editButton}
              className={styles.edit}
              disabled={groups.length === 0}
              onClick={() => setMode("order")}
              aria-label="그룹과 즐겨찾기 순서 편집"
            >
              <img src={editIcon} width="20" height="20" alt="" draggable={false} />
            </button>
          </span>
        )}
      </h2>
      <FavoritesNotice resource={resource} onRetry={reload} />
      <ul className={styles.list}>
        {groups.map((group, index) => {
          const info = (
            <span className={styles.info}>
              <span className={styles.name}>
                {group.name}
                {!deleting && index === 0 && " · 대표 그룹"}
              </span>
              <span className={styles.count}>
                {group.favorites.length} / {MAX_FAVORITES_PER_GROUP}
              </span>
            </span>
          );
          return (
            <li key={group.id}>
              {deleting ? (
                index === 0 ? (
                  <div className={styles.group}>
                    {info}
                    <span className={styles.representative}>대표 그룹</span>
                  </div>
                ) : (
                  <label className={`${styles.group} ${styles.selection}`}>
                    {info}
                    <input
                      className={styles.checkbox}
                      type="checkbox"
                      aria-label={`${group.name} 삭제 선택`}
                      checked={selected.includes(group.id)}
                      disabled={busy || resource.status !== "success"}
                      onChange={(event) => {
                        setError(null);
                        const ids = targets.map((target) => target.id);
                        setSelected(event.target.checked ? [...ids, group.id] : ids.filter((id) => id !== group.id));
                      }}
                    />
                  </label>
                )
              ) : (
                <Link className={styles.group} to={`/mypage/groups/${encodeURIComponent(group.id)}`}>
                  {info}
                  <img src={chevronIcon} width="18" height="18" alt="" draggable={false} />
                </Link>
              )}
            </li>
          );
        })}
      </ul>
      {deleting ? (
        <button
          ref={selectionButton}
          className={styles.deleteSelected}
          disabled={busy || changed || selected.length === 0 || resource.status !== "success"}
          onClick={(event) => {
            event.currentTarget.focus();
            setError(null);
            setConfirming(true);
          }}
        >
          선택한 그룹 {selected.length}개 삭제
        </button>
      ) : (
        resource.status === "success" &&
        groups.length < MAX_FAVORITE_GROUPS && (
          <Link className={styles.add} to="/mypage/groups/new">
            <img src={plusIcon} width="18" height="18" alt="" draggable={false} />새 그룹 만들기
          </Link>
        )
      )}
      {changed && !confirming && (
        <p className={formStyles.error} role="alert">
          그룹 목록이 변경됐어요. 다시 선택해 주세요.
        </p>
      )}
      {confirming && (
        <ConfirmDialog
          title={`선택한 그룹 ${selected.length}개를\n삭제하시겠습니까?`}
          className={styles.dialog}
          titleClassName={styles.dialogTitle}
          fallbackFocus={selectionButton}
          onCancel={() => {
            if (!busy) setConfirming(false);
          }}
        >
          {(error || changed) && (
            <p className={formStyles.error} role="alert">
              {deletedCount > 0 && "일부 그룹은 삭제됐어요. 남은 그룹만 다시 삭제할 수 있어요. "}
              {changed ? "그룹 목록이 변경됐어요. 취소하고 다시 선택해 주세요." : error}
            </p>
          )}
          <div className={styles.dialogActions}>
            <button
              className={styles.confirm}
              disabled={busy || changed || selected.length === 0 || resource.status !== "success"}
              onClick={() => void removeSelected()}
            >
              {busy ? "삭제 중…" : "삭제"}
            </button>
            <button className={styles.cancel} disabled={busy} onClick={() => setConfirming(false)}>
              취소
            </button>
          </div>
        </ConfirmDialog>
      )}
      {notice.id > 0 && <Notice key={notice.id} message={notice.message} />}
    </section>
  );
}
