import { directionLabelOf } from "@/shared/utils/directionLabelOf";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router";
import { deleteFavorite, deleteFavoriteGroup } from "@/shared/api/favorites.api";
import type { FavoriteGroup, SavedFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { LoadError } from "@/shared/components/LoadError";
import { RetryButton } from "@/shared/components/RetryButton";
import { useHomeFavorites } from "@/shared/hooks/useFavorites";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import { RouteBadge } from "@/shared/components/RouteBadge";
import { EmptyFavoriteGroup } from "@/shared/components/EmptyFavoriteGroup";
import { favoriteStopLabelOf, sameFavorite, FAVORITES_LOAD_FAILURE_TITLE } from "@/shared/utils/favoritePolicy";
import * as formStyles from "@/shared/styles/form.css";
import { isNativeNotificationSupported } from "@/shared/notification/nativeNotification";
import { useNotificationSettings } from "../hooks/useNotificationSettings";
import { NotificationRow } from "./NotificationRow";
import * as styles from "./GroupDetails.css";

interface GroupDetailsProps {
  group: FavoriteGroup;
  representative: boolean;
}

export function GroupDetails({ group, representative }: GroupDetailsProps) {
  const [deleting, setDeleting] = useState<"group" | SavedFavorite | null>(null);
  const [revision, retry] = useState(0);
  const { resource: summary, requestState } = useHomeFavorites({ kind: "group", groupId: group.id }, revision);
  const favorites = latestSuccessOf(summary)?.body.groups[0]?.favorites ?? [];
  const failed = summary.status === "loadingError";
  const { busy, error, clearError, run } = useFavoriteAction();
  const notificationSettings = useNotificationSettings(isNativeNotificationSupported());
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
    <section {...maskedPropsFor()}>
      {failed ? (
        <FavoritesNotice resource={summary} requestState={requestState} onRetry={() => retry((value) => value + 1)}>
          {({ retry, retrying }) => (
            <LoadError title={FAVORITES_LOAD_FAILURE_TITLE} description="잠시 후 다시 시도해 주세요.">
              <RetryButton onRetry={retry} busy={retrying}>
                {retrying ? "불러오는 중…" : "다시 시도하기"}
              </RetryButton>
            </LoadError>
          )}
        </FavoritesNotice>
      ) : (
        <FavoritesNotice resource={summary} requestState={requestState} onRetry={() => retry((value) => value + 1)} />
      )}
      <div hidden={failed}>
        <ul className={styles.list}>
          {group.favorites.map((favorite, index) => {
            const item = favorites.find((candidate) => candidate.id === favorite.id);
            const stopLabel = favoriteStopLabelOf(item, index);
            return (
              <li className={styles.card} key={favorite.id}>
                <span className={styles.info}>
                  <span className={styles.cardHeading}>
                    <RouteBadge>{item?.route.displayName || "노선 정보 없음"}</RouteBadge>
                    <span className={styles.stop}>{stopLabel}</span>
                  </span>
                  <span className={styles.direction}>
                    {directionLabelOf(item?.route.terminalStopName, favorite.direction)}
                  </span>
                </span>
                <button
                  className={styles.remove}
                  aria-label={`${stopLabel} 삭제`}
                  onClick={(event) => {
                    event.currentTarget.focus();
                    clearError();
                    setDeleting(favorite);
                  }}
                >
                  삭제
                </button>
                {notificationSettings.status === "ready" && (
                  <NotificationRow
                    target={favorite}
                    stopLabel={stopLabel}
                    initialSetting={
                      notificationSettings.settings.find((setting) => sameFavorite(setting, favorite)) ?? null
                    }
                  />
                )}
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
              clearError();
              setDeleting("group");
            }}
          >
            그룹 삭제
          </button>
        </div>
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
                <span className={styles.targetStop}>
                  {favoriteStopLabelOf(
                    deletingItem,
                    group.favorites.findIndex((favorite) => favorite.id === deleting.id),
                  )}
                </span>
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
                <button className={styles.cancel} disabled={busy} onClick={() => setDeleting(null)}>
                  취소
                </button>
                <button
                  className={styles.confirm}
                  disabled={busy}
                  onClick={() =>
                    void run(
                      () =>
                        deleting === "group" ? deleteFavoriteGroup(group.id) : deleteFavorite(group.id, deleting.id),
                      {
                        kind: deleting === "group" ? "deleteGroup" : "deleteFavorite",
                      },
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
              </>
            )}
          </div>
        </ConfirmDialog>
      )}
    </section>
  );
}
