import { directionLabelOf } from "@/shared/utils/directionLabelOf";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { deleteFavorite, deleteFavoriteGroup } from "@/shared/api/favorites.api";
import type { FavoriteGroup, SavedFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { useHomeFavorites } from "@/shared/hooks/useFavorites";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { EmptyFavoriteGroup } from "@/shared/components/EmptyFavoriteGroup";
import * as formStyles from "@/shared/styles/form.css";
import * as styles from "./GroupDetails.css";

interface GroupDetailsProps {
  group: FavoriteGroup;
  representative: boolean;
}

export function GroupDetails({ group, representative }: GroupDetailsProps) {
  const [deleting, setDeleting] = useState<"group" | SavedFavorite | null>(null);
  const [revision, retry] = useState(0);
  const summary = useHomeFavorites({ kind: "group", groupId: group.id }, revision);
  const favorites = latestSuccessOf(summary)?.body.groups[0]?.favorites ?? [];
  const { busy, error, setError, run } = useFavoriteAction();
  const navigate = useNavigate();
  const groupDeleteButton = useRef<HTMLButtonElement>(null);
  const focusAfterDelete = useRef(false);
  useEffect(() => {
    if (deleting === null && focusAfterDelete.current) {
      focusAfterDelete.current = false;
      groupDeleteButton.current?.focus();
    }
  }, [deleting]);
  const deletingItem =
    deleting !== null && deleting !== "group" ? favorites.find((item) => item.id === deleting.id) : undefined;
  const lastCode = group.name.charCodeAt(group.name.length - 1);
  const objectParticle =
    lastCode >= 0xac00 && lastCode <= 0xd7a3 ? ((lastCode - 0xac00) % 28 === 0 ? "를" : "을") : " 그룹을";
  const protectedGroup = deleting === "group" && representative;
  return (
    <section className="amp-mask">
      <FavoritesNotice resource={summary} onRetry={() => retry((value) => value + 1)} />
      <ul className={styles.list}>
        {group.favorites.map((favorite, index) => {
          const item = favorites.find((candidate) => candidate.id === favorite.id);
          return (
            <li className={styles.card} key={favorite.id}>
              <span className={styles.info}>
                <span className={styles.cardHeading}>
                  <RouteBadge>{item?.route.displayName || "노선 정보 없음"}</RouteBadge>
                  <span className={styles.stop}>
                    {item ? item.stop.name || "정류장 정보 없음" : `즐겨찾기 ${index + 1}`}
                  </span>
                </span>
                <span className={styles.direction}>
                  {directionLabelOf(item?.route.terminalStopName, favorite.direction)}
                </span>
              </span>
              <button
                className={styles.remove}
                aria-label={`${item?.stop.name || `즐겨찾기 ${index + 1}`} 삭제`}
                onClick={(event) => {
                  event.currentTarget.focus();
                  setError(null);
                  setDeleting(favorite);
                }}
              >
                삭제
              </button>
            </li>
          );
        })}
      </ul>
      {group.favorites.length === 0 && <EmptyFavoriteGroup />}
      <div className={styles.groupActions}>
        <button
          ref={groupDeleteButton}
          className={styles.deleteGroup}
          onClick={(event) => {
            event.currentTarget.focus();
            setError(null);
            setDeleting("group");
          }}
        >
          그룹 삭제
        </button>
      </div>
      {deleting !== null && (
        <ConfirmDialog
          className={deleting === "group" ? styles.dialog : `${styles.dialog} ${styles.favoriteDialog}`}
          titleClassName={deleting === "group" ? styles.groupTitle : styles.dialogTitle}
          fallbackFocus={groupDeleteButton}
          title={
            protectedGroup
              ? "대표 그룹은 삭제할 수 없어요"
              : deleting === "group"
                ? `${group.name}${objectParticle} 삭제할까요?`
                : "정말 삭제 하시겠습니까?"
          }
          onCancel={() => {
            if (!busy) setDeleting(null);
          }}
        >
          {deleting !== "group" && (
            <div className={styles.target}>
              <div className={styles.targetHeading}>
                <RouteBadge>{deletingItem?.route.displayName || "노선 정보 없음"}</RouteBadge>
                <span className={styles.targetStop}>{deletingItem?.stop.name || "정류장 정보 없음"}</span>
              </div>
              <span className={styles.direction}>
                {directionLabelOf(deletingItem?.route.terminalStopName, deleting.direction)}
              </span>
            </div>
          )}
          {deleting === "group" && (
            <p className={styles.description}>
              {protectedGroup
                ? "첫 번째로 설정된 대표 그룹은 삭제할 수 없어요."
                : `이 그룹과 즐겨찾기 ${group.favorites.length}개가 함께 삭제돼요. 다른 그룹에 저장된 즐겨찾기는 그대로 유지돼요.`}
            </p>
          )}
          {error && (
            <p className={formStyles.error} role="alert">
              {error}
            </p>
          )}
          <div className={deleting === "group" ? styles.groupDialogActions : styles.dialogActions}>
            {protectedGroup ? (
              <button className={styles.confirm} onClick={() => setDeleting(null)}>
                확인
              </button>
            ) : (
              <>
                <button
                  className={styles.confirm}
                  disabled={busy}
                  onClick={() =>
                    void run(() =>
                      deleting === "group" ? deleteFavoriteGroup(group.id) : deleteFavorite(group.id, deleting.id),
                    ).then((ok) => {
                      if (!ok) return;
                      focusAfterDelete.current = deleting !== "group";
                      setDeleting(null);
                      if (deleting === "group")
                        void navigate("/mypage", { replace: true, state: { notice: "그룹을 삭제했어요" } });
                    })
                  }
                >
                  {busy ? "삭제 중…" : "삭제"}
                </button>
                <button className={styles.cancel} disabled={busy} onClick={() => setDeleting(null)}>
                  취소
                </button>
              </>
            )}
          </div>
        </ConfirmDialog>
      )}
    </section>
  );
}
