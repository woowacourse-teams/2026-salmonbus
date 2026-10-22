import { useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router";
import {
  createFavoriteGroup,
  deleteFavorite,
  deleteFavoriteGroup,
  renameFavoriteGroup,
} from "@/shared/api/favorites.api";
import type { FavoriteGroup, SavedFavorite } from "@/shared/api/favorites.types";
import { latestSuccessOf } from "@/shared/api/polledResource";
import { AccountLayout } from "@/shared/components/AccountLayout";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { LoginButton } from "@/shared/components/LoginButton";
import { useAuth } from "@/shared/hooks/useAuth";
import { useFavorites, useHomeFavorites } from "@/features/favorites/FavoritesContext";
import { FavoritesNotice } from "@/features/favorites/FavoritesNotice";
import { OrderDialog } from "@/features/favorites/OrderDialog";
import { groupNameError } from "@/features/favorites/favoritePolicy";
import { useFavoriteAction } from "@/features/favorites/useFavoriteAction";
import * as buttons from "@/shared/styles/button.css";
import * as styles from "@/features/favorites/favorites.css";

export function FavoriteGroupPage() {
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
          <p role="status" className={styles.subtle}>
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
                <p className={styles.error} role="alert">
                  그룹은 3개까지 만들 수 있어요.
                </p>
              )
            )
          ) : group ? (
            <GroupDetails key={group.id} group={group} />
          ) : (
            resource.status === "success" && (
              <p className={styles.error} role="alert">
                그룹을 찾지 못했어요.
              </p>
            )
          )}
        </>
      )}
    </AccountLayout>
  );
}

function GroupNameForm({ group, onRenamed }: { group?: FavoriteGroup; onRenamed?: () => void }) {
  const [name, setName] = useState(group?.name ?? "");
  const { busy, error, setError, run } = useFavoriteAction();
  const navigate = useNavigate();
  const invalid = groupNameError(name);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (invalid || name.trim() === group?.name) return;
    if (await run(() => (group ? renameFavoriteGroup(group.id, name) : createFavoriteGroup(name)))) {
      if (onRenamed) onRenamed();
      else void navigate("/mypage", { replace: true, state: { notice: "그룹을 만들었어요" } });
    }
  }
  return (
    <form className="amp-mask" onSubmit={(event) => void submit(event)}>
      <label htmlFor="group-name">그룹 이름</label>
      <input
        id="group-name"
        className={styles.field}
        value={name}
        autoComplete="off"
        aria-describedby="group-name-help group-name-error"
        aria-invalid={error !== null || (name !== "" && invalid !== null)}
        onChange={(event) => {
          setName(event.target.value);
          setError(null);
        }}
      />
      <p id="group-name-help" className={styles.subtle}>
        앞뒤 공백을 제외한 1~10자
        <br />
        특수문자와 이모지도 사용할 수 있어요.
      </p>
      <p id="group-name-error" className={styles.error} role="alert">
        {error ?? (name !== "" ? invalid : null)}
      </p>
      <button className={buttons.primary} disabled={busy || invalid !== null || name.trim() === group?.name}>
        {busy ? "저장 중…" : group ? "저장" : "만들기"}
      </button>
    </form>
  );
}

function GroupDetails({ group }: { group: FavoriteGroup }) {
  const { groups } = useFavorites();
  const [editing, setEditing] = useState(false);
  const [ordering, setOrdering] = useState(false);
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
          <button className={styles.action} onClick={() => setEditing(false)}>
            취소
          </button>
        </h2>
        <GroupNameForm group={group} onRenamed={() => setEditing(false)} />
      </>
    );
  const lastGroup = deleting === "group" && groups.length === 1;
  return (
    <section className="amp-mask">
      <div className={styles.heading}>
        <span>즐겨찾기 {group.favorites.length} / 2</span>
        <button className={styles.action} aria-label="그룹 이름 변경" onClick={() => setEditing(true)}>
          이름 변경
        </button>
      </div>
      <FavoritesNotice resource={summary} onRetry={() => retry((value) => value + 1)} />
      <ul className={styles.list}>
        {group.favorites.map((favorite, index) => {
          const item = favorites.find((candidate) => candidate.id === favorite.id);
          return (
            <li className={styles.groupRow} key={favorite.id}>
              <span>
                {item ? `${item.route.displayName} · ${item.stop.name}` : `즐겨찾기 ${index + 1}`}
                <span className={styles.subtle} style={{ display: "block" }}>
                  {item ? `${item.route.directionName} 방면` : favorite.direction === "UP" ? "상행" : "하행"}
                </span>
              </span>
              <button
                className={styles.action}
                aria-label={`${item?.stop.name ?? `즐겨찾기 ${index + 1}`} 삭제`}
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
      {group.favorites.length === 0 && <p className={styles.subtle}>아직 등록한 즐겨찾기가 없어요.</p>}
      <div className={styles.actions}>
        <button className={styles.action} onClick={() => setOrdering(true)}>
          순서 편집
        </button>
        <button
          className={styles.action}
          onClick={() => {
            setError(null);
            setDeleting("group");
          }}
        >
          그룹 삭제
        </button>
      </div>
      {ordering && <OrderDialog onClose={() => setOrdering(false)} />}
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
          <p className={styles.subtle}>
            {lastGroup
              ? "즐겨찾기를 담을 그룹을 한 개 이상 남겨 주세요."
              : deleting === "group"
                ? "그룹 안의 즐겨찾기도 함께 삭제돼요. 다른 그룹의 즐겨찾기는 유지돼요."
                : "이 그룹에서만 즐겨찾기를 삭제해요."}
          </p>
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          <div className={styles.actions}>
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
