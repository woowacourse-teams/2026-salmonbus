import { useEffect, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { AccountLayout } from "@/shared/components/AccountLayout";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { LoginButton } from "@/shared/components/LoginButton";
import { Notice } from "@/shared/components/Notice";
import { GuestMyPage } from "./GuestMyPage";
import { GroupList } from "./GroupList";
import { useAuth } from "@/shared/hooks/useAuth";
import { authErrorOf } from "@/shared/utils/authErrorOf";
import * as buttonStyles from "@/shared/styles/button.css";
import pencilIcon from "../assets/pencil.svg";
import * as styles from "./MyPageScreen.css";
import { pageEnter } from "@/shared/styles/pageTransition.css";

export function MyPageScreen() {
  const active = useRef(false);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  const { state, reload, signOut, withdraw } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const [action, setAction] = useState<"logout" | "withdraw" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const notice: unknown = location.state?.notice;

  async function confirm() {
    if (busy || action === null) return;
    setBusy(true);
    setError(null);
    const result = await (action === "logout" ? signOut() : withdraw());
    if (!active.current) return;
    setBusy(false);
    if (!result.ok) {
      setError(authErrorOf(result.failure));
      return;
    }
    if (result.body !== undefined) {
      window.location.assign(result.body.startUrl);
      return;
    }
    void navigate("/", {
      replace: true,
      state: { notice: action === "logout" ? "로그아웃했어요" : "회원탈퇴가 완료됐어요" },
    });
  }

  if (state.status === "anonymous")
    return (
      <AccountLayout contentClassName={pageEnter}>
        <GuestMyPage />
        {typeof notice === "string" && <Notice key={notice} message={notice} />}
      </AccountLayout>
    );

  return (
    <AccountLayout contentClassName={pageEnter}>
      <h1 className={styles.title}>마이페이지</h1>
      {state.status === "authenticated" ? (
        <>
          <div className="amp-mask">
            <div className={styles.nickname}>
              <span>{state.member.nickname}님</span>
              <Link className={styles.nicknameEdit} to="/mypage/nickname" aria-label="닉네임 수정">
                <img src={pencilIcon} width="20" height="20" alt="" draggable={false} />
              </Link>
            </div>
            <p className={styles.caption}>
              {state.member.provider === "KAKAO" ? "카카오" : "Apple"} 계정으로 로그인 중
            </p>
          </div>
          <GroupList />
          <button
            className={styles.row}
            type="button"
            onClick={() => {
              setError(null);
              setAction("logout");
            }}
          >
            로그아웃<span aria-hidden="true">›</span>
          </button>
          <button
            className={styles.row}
            type="button"
            onClick={() => {
              setError(null);
              setAction("withdraw");
            }}
          >
            회원탈퇴<span aria-hidden="true">›</span>
          </button>
        </>
      ) : state.status === "loading" ? (
        <p role="status" className={styles.caption}>
          로그인 상태를 확인하고 있어요…
        </p>
      ) : (
        <>
          <LoginButton returnTo="/mypage" />
          {state.status === "error" && (
            <>
              <p className={styles.error} role="alert">
                로그인 상태를 확인하지 못했어요.
              </p>
              <button className={styles.row} type="button" onClick={() => void reload()}>
                다시 확인하기
              </button>
            </>
          )}
          {state.status === "unavailable" && <p className={styles.caption}>로그인 기능을 준비 중이에요.</p>}
        </>
      )}
      {typeof notice === "string" && <Notice key={notice} message={notice} />}
      {action !== null && (
        <ConfirmDialog
          title={action === "logout" ? "로그아웃 하시겠어요?" : "정말 탈퇴하시겠어요?"}
          onCancel={() => {
            if (!busy) setAction(null);
          }}
        >
          {action === "withdraw" && <p className={styles.caption}>탈퇴하면 이 계정의 회원 정보가 삭제돼요.</p>}
          {action === "withdraw" && state.status === "authenticated" && state.member.provider === "APPLE" && (
            <p className={styles.caption}>탈퇴를 완료하려면 Apple 계정으로 한 번 더 인증해야 해요.</p>
          )}
          {error && (
            <p className={styles.error} role="alert">
              {error}
            </p>
          )}
          <div className={styles.actions}>
            <button type="button" className={buttonStyles.primary} disabled={busy} onClick={() => void confirm()}>
              {busy ? "처리 중…" : action === "logout" ? "로그아웃" : "회원탈퇴"}
            </button>
            <button type="button" className={buttonStyles.secondary} disabled={busy} onClick={() => setAction(null)}>
              취소
            </button>
          </div>
        </ConfirmDialog>
      )}
    </AccountLayout>
  );
}
