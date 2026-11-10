import { visuallyHidden } from "@/shared/styles/accessibility.css";
import { useEffect, useRef, useState } from "react";
import { Link, useNavigate } from "react-router";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { LoginButton } from "@/shared/components/LoginButton";
import { GuestMyPage } from "./GuestMyPage";
import { PrivacySection } from "./PrivacySection";
import { WhatsNewLink } from "./WhatsNewLink";
import { useAuth } from "@/shared/hooks/useAuth";
import { withdrawAppleInApp } from "@/shared/native-auth/nativeSignIn";
import { authErrorOf } from "@/shared/utils/authErrorOf";
import * as buttonStyles from "@/shared/styles/button.css";
import pencilIcon from "../assets/pencil.svg";
import * as styles from "./MyPageScreen.css";
import { menuRow } from "./menuRow.css";
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
  const navigate = useNavigate();
  const [action, setAction] = useState<"logout" | "withdraw" | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function confirm() {
    if (busy || action === null) return;
    setBusy(true);
    setError(null);
    const result = await (action === "logout" ? signOut() : withdraw());
    if (!active.current) return;
    if (result.ok && result.body !== undefined) {
      await withdrawAppleMember(result.body.startUrl);
      return;
    }
    setBusy(false);
    if (!result.ok) {
      setError(
        authErrorOf(
          result.failure,
          action === "logout"
            ? "로그아웃하지 못했어요. 다시 시도해 주세요."
            : "회원탈퇴를 완료하지 못했어요. 다시 시도해 주세요.",
        ),
      );
      return;
    }
    void navigate("/", {
      replace: true,
      state: { notice: action === "logout" ? "로그아웃했어요" : "회원탈퇴가 완료됐어요" },
    });
  }

  // 앱에서는 바텀시트로 다시 인증한다. 브라우저이거나 서버에 앱 탈퇴 API가 없으면 웹 Apple 재인증 화면으로 간다.
  async function withdrawAppleMember(startUrl: string) {
    const outcome = await withdrawAppleInApp();
    if (!active.current) return;
    if (outcome === "unsupported") {
      window.location.assign(startUrl);
      return;
    }
    setBusy(false);
    if (outcome === "failed") {
      setError("회원탈퇴를 완료하지 못했어요. 다시 시도해 주세요.");
      return;
    }
    setAction(null);
    if (outcome === "withdrawn") await reload();
    void navigate(outcome === "withdrawn" ? "/" : "/mypage", {
      replace: true,
      state: { notice: outcome === "withdrawn" ? "회원탈퇴가 완료됐어요" : "회원탈퇴를 취소했어요" },
    });
  }

  if (state.status === "anonymous")
    return (
      <section className={pageEnter}>
        <GuestMyPage />
      </section>
    );

  return (
    <section className={pageEnter}>
      <h1 className={visuallyHidden}>마이페이지</h1>
      {state.status === "authenticated" ? (
        <>
          <div {...maskedPropsFor()}>
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
          <WhatsNewLink />
          <button
            className={menuRow}
            type="button"
            onClick={() => {
              setError(null);
              setAction("logout");
            }}
          >
            로그아웃<span aria-hidden="true">›</span>
          </button>
          <button
            className={menuRow}
            type="button"
            onClick={() => {
              setError(null);
              setAction("withdraw");
            }}
          >
            회원탈퇴<span aria-hidden="true">›</span>
          </button>
          <PrivacySection />
        </>
      ) : state.status === "loading" ? (
        <p role="status" className={styles.caption}>
          로그인 상태를 확인하고 있어요…
        </p>
      ) : (
        <>
          <LoginButton returnTo="/mypage" menuRowClassName={menuRow} />
          {state.status === "error" && (
            <>
              <p className={styles.error} role="alert">
                로그인 상태를 확인하지 못했어요.
              </p>
              <button className={menuRow} type="button" onClick={() => void reload()}>
                다시 시도하기
              </button>
            </>
          )}
          {state.status === "unavailable" && <p className={styles.caption}>로그인 기능을 준비 중이에요.</p>}
          <WhatsNewLink />
        </>
      )}
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
            <button type="button" className={buttonStyles.secondary} disabled={busy} onClick={() => setAction(null)}>
              취소
            </button>
            <button type="button" className={buttonStyles.primary} disabled={busy} onClick={() => void confirm()}>
              {busy ? "처리 중…" : action === "logout" ? "로그아웃" : "회원탈퇴"}
            </button>
          </div>
        </ConfirmDialog>
      )}
    </section>
  );
}
