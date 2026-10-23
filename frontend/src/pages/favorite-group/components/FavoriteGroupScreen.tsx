import { MAX_FAVORITE_GROUPS } from "@/shared/utils/favoritePolicy";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { AccountLayout } from "@/shared/components/AccountLayout";
import { LoginButton } from "@/shared/components/LoginButton";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { useAuth } from "@/shared/hooks/useAuth";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { GroupNameForm } from "./GroupNameForm";
import { GroupDetails } from "./GroupDetails";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import * as styles from "./FavoriteGroupScreen.css";
import backArrow from "../assets/back-arrow.svg";

export function FavoriteGroupScreen() {
  const { groupId = "" } = useParams();
  const { auth, groups, resource, reload } = useFavorites();
  const { reload: reloadAuth } = useAuth();
  const [editingId, setEditingId] = useState<string | null>(null);
  const group = groups.find((candidate) => candidate.id === groupId);
  const representative = group !== undefined && groups[0]?.id === group.id;
  const editing = auth.status === "authenticated" && group !== undefined && editingId === group.id;
  return (
    <AccountLayout>
      <div className={styles.screen}>
        <header className={`${styles.title} amp-mask`}>
          {editing ? (
            <button
              type="button"
              className={styles.back}
              aria-label="그룹 관리로 돌아가기"
              onClick={() => setEditingId(null)}
            >
              <img src={backArrow} width="17" height="17" alt="" draggable={false} />
            </button>
          ) : (
            <Link to="/mypage" className={styles.back} aria-label="마이페이지로 돌아가기">
              <img src={backArrow} width="17" height="17" alt="" draggable={false} />
            </Link>
          )}
          <div className={styles.titleLabel}>
            <h1 className={styles.name}>
              {editing ? "이름 변경" : groupId === "new" ? "그룹 만들기" : (group?.name ?? "그룹 관리")}
            </h1>
            {!editing && representative && <span className={styles.representative}>대표</span>}
          </div>
          {group && auth.status === "authenticated" && editingId !== group.id && (
            <button
              type="button"
              className={styles.rename}
              aria-label="그룹 이름 변경"
              onClick={() => setEditingId(group.id)}
            >
              이름 변경
            </button>
          )}
        </header>
        {auth.status === "anonymous" ? (
          <LoginButton returnTo="/mypage" />
        ) : auth.status !== "authenticated" ? (
          <>
            <p role="status" className={formStyles.subtle}>
              {auth.status === "loading" ? "로그인 상태를 확인하고 있어요…" : "로그인 상태를 확인할 수 없어요."}
            </p>
            {auth.status === "error" && (
              <button className={buttons.secondary} onClick={() => void reloadAuth()}>
                다시 확인하기
              </button>
            )}
          </>
        ) : (
          <>
            <FavoritesNotice resource={resource} onRetry={reload} />
            {groupId === "new" ? (
              groups.length < MAX_FAVORITE_GROUPS && resource.status === "success" ? (
                <GroupNameForm />
              ) : (
                resource.status === "success" && (
                  <p className={formStyles.error} role="alert">
                    {`그룹은 ${MAX_FAVORITE_GROUPS}개까지 만들 수 있어요.`}
                  </p>
                )
              )
            ) : group ? (
              editing ? (
                <GroupNameForm key={group.id} group={group} onRenamed={() => setEditingId(null)} />
              ) : (
                <GroupDetails key={group.id} group={group} representative={representative} />
              )
            ) : (
              resource.status === "success" && (
                <p className={formStyles.error} role="alert">
                  그룹을 찾지 못했어요.
                </p>
              )
            )}
          </>
        )}
      </div>
    </AccountLayout>
  );
}
