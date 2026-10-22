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

export function FavoriteGroupScreen() {
  const { groupId = "" } = useParams();
  const { auth, groups, resource, reload } = useFavorites();
  const { reload: reloadAuth } = useAuth();
  const group = groups.find((candidate) => candidate.id === groupId);
  return (
    <AccountLayout>
      <header className={`${styles.title} amp-mask`}>
        <Link to="/mypage" className={styles.back} aria-label="마이페이지로 돌아가기">
          <svg
            width="17"
            height="17"
            viewBox="0 0 17 17"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M13.6 8.5H3.4M7.9 4 3.4 8.5 7.9 13" />
          </svg>
        </Link>
        <h1 style={{ margin: 0, fontSize: "inherit" }}>
          {groupId === "new" ? "그룹 만들기" : (group?.name ?? "그룹 관리")}
        </h1>
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
            groups.length < 3 && resource.status === "success" ? (
              <GroupNameForm />
            ) : (
              resource.status === "success" && (
                <p className={formStyles.error} role="alert">
                  그룹은 3개까지 만들 수 있어요.
                </p>
              )
            )
          ) : group ? (
            <GroupDetails key={group.id} group={group} />
          ) : (
            resource.status === "success" && (
              <p className={formStyles.error} role="alert">
                그룹을 찾지 못했어요.
              </p>
            )
          )}
        </>
      )}
    </AccountLayout>
  );
}
