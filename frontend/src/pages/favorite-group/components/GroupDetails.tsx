import { useState } from "react";
import { useNavigate } from "react-router";
import { deleteFavorite, deleteFavoriteGroup } from "@/shared/api/favorites.api";
import type { FavoriteGroup, SavedFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { useFavorites, useHomeFavorites } from "@/shared/hooks/useFavorites";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { GroupNameForm } from "./GroupNameForm";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import * as styles from "./GroupDetails.css";

interface GroupDetailsProps {
  group: FavoriteGroup;
  editing: boolean;
  onEditEnd: () => void;
}

export function GroupDetails({ group, editing, onEditEnd }: GroupDetailsProps) {
  const { groups } = useFavorites();
  const [deleting, setDeleting] = useState<"group" | SavedFavorite | null>(null);
  const [revision, retry] = useState(0);
  const summary = useHomeFavorites(group.id, revision);
  const favorites = latestSuccessOf(summary)?.body.groups[0]?.favorites ?? [];
  const { busy, error, setError, run } = useFavoriteAction();
  const navigate = useNavigate();
  if (editing)
    return (
      <>
        <h2 className={styles.heading}>
          그룹 이름 변경
          <button className={buttons.text} onClick={onEditEnd}>
            취소
          </button>
        </h2>
        <GroupNameForm group={group} onRenamed={onEditEnd} />
      </>
    );
  const lastGroup = deleting === "group" && groups.length === 1;
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
                  {item?.route.terminalStopName?.trim()
                    ? `${item.route.terminalStopName.trim()} 방면`
                    : favorite.direction === "UP"
                      ? "상행"
                      : "하행"}
                </span>
              </span>
              <button
                className={styles.remove}
                aria-label={`${item?.stop.name || `즐겨찾기 ${index + 1}`} 삭제`}
                onClick={() => {
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
      {group.favorites.length === 0 && <p className={formStyles.subtle}>아직 등록한 즐겨찾기가 없어요.</p>}
      <div className={styles.groupActions}>
        <button
          className={styles.deleteGroup}
          onClick={() => {
            setError(null);
            setDeleting("group");
          }}
        >
          그룹 삭제
        </button>
      </div>
      {deleting !== null && (
        <ConfirmDialog
          title={
            lastGroup
              ? "마지막 그룹은 삭제할 수 없어요"
              : deleting === "group"
                ? `${group.name} 그룹을 삭제할까요?`
                : "즐겨찾기를 삭제할까요?"
          }
          onCancel={() => {
            if (!busy) setDeleting(null);
          }}
        >
          <p className={formStyles.subtle}>
            {lastGroup
              ? "즐겨찾기를 담을 그룹을 한 개 이상 남겨 주세요."
              : deleting === "group"
                ? "그룹 안의 즐겨찾기도 함께 삭제돼요. 다른 그룹의 즐겨찾기는 유지돼요."
                : "이 그룹에서만 즐겨찾기를 삭제해요."}
          </p>
          {error && (
            <p className={formStyles.error} role="alert">
              {error}
            </p>
          )}
          <div className={formStyles.actions}>
            {lastGroup ? (
              <button className={buttons.primary} onClick={() => setDeleting(null)}>
                확인
              </button>
            ) : (
              <>
                <button
                  className={buttons.primary}
                  disabled={busy}
                  onClick={() =>
                    void run(() =>
                      deleting === "group" ? deleteFavoriteGroup(group.id) : deleteFavorite(group.id, deleting.id),
                    ).then((ok) => {
                      if (!ok) return;
                      setDeleting(null);
                      if (deleting === "group")
                        void navigate("/mypage", { replace: true, state: { notice: "그룹을 삭제했어요" } });
                    })
                  }
                >
                  {busy ? "삭제 중…" : "삭제"}
                </button>
                <button className={buttons.secondary} disabled={busy} onClick={() => setDeleting(null)}>
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
