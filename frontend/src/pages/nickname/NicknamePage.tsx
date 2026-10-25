import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { AccountLayout } from "@/shared/components/AccountLayout";
import { useStillOnScreen } from "@/shared/routing/stillOnScreen";
import { LoginButton } from "@/shared/components/LoginButton";
import { useAuth } from "@/shared/hooks/useAuth";
import { authErrorOf } from "@/shared/utils/authErrorOf";
import { nicknameErrorOf } from "./components/nicknamePolicy";
import * as buttonStyles from "@/shared/styles/button.css";
import * as styles from "./NicknamePage.css";

export function NicknamePage() {
  const { state, reload } = useAuth();
  return (
    <AccountLayout>
      <div className={styles.backTitle}>
        <Link className={styles.backIcon} to="/mypage" aria-label="마이페이지로 돌아가기">
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
        <h1 className={styles.title} style={{ margin: 0 }}>
          닉네임 수정
        </h1>
      </div>
      {state.status === "authenticated" ? (
        <NicknameForm key={state.member.id} current={state.member.nickname} />
      ) : state.status === "loading" ? (
        <p role="status">로그인 상태를 확인하고 있어요…</p>
      ) : state.status === "error" ? (
        <>
          <p className={styles.error} role="alert">
            로그인 상태를 확인하지 못했어요.
          </p>
          <button className={buttonStyles.secondary} type="button" onClick={() => void reload()}>
            다시 확인하기
          </button>
        </>
      ) : (
        <LoginButton returnTo="/mypage" />
      )}
    </AccountLayout>
  );
}

function NicknameForm({ current }: { current: string }) {
  const isStillOnScreen = useStillOnScreen();
  const { changeNickname } = useAuth();
  const navigate = useNavigate();
  const [nickname, setNickname] = useState(current);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const localError = nicknameErrorOf(nickname);

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (busy || localError !== null || nickname.trim() === current) return;
    setBusy(true);
    setError(null);
    const result = await changeNickname(nickname);
    setBusy(false);
    if (!result.ok) {
      setError(authErrorOf(result.failure));
      return;
    }
    // 화면을 떠나는 중이어도 폼은 남아 있을 수 있으니 busy와 오류는 늘 정리하고, 마이페이지로 옮기는 건 아직 이 화면에 있을 때만 한다.
    if (isStillOnScreen()) void navigate("/mypage", { replace: true, state: { notice: "닉네임을 변경했어요" } });
  }

  return (
    <form onSubmit={(event) => void submit(event)} className="amp-mask">
      <label className={styles.label} htmlFor="nickname">
        닉네임
      </label>
      <input
        id="nickname"
        name="nickname"
        className={styles.field}
        value={nickname}
        maxLength={30}
        autoComplete="off"
        spellCheck={false}
        aria-invalid={error !== null || (nickname !== current && localError !== null)}
        aria-describedby="nickname-rules nickname-error"
        onChange={(event) => {
          setNickname(event.target.value);
          setError(null);
        }}
      />
      <p id="nickname-error" className={styles.error} role="alert">
        {error ?? (nickname !== current ? localError : null)}
      </p>
      <p id="nickname-rules" className={styles.caption}>
        앞뒤 공백을 제외한 2~10자
        <br />
        한글 · 영문 · 숫자만 사용할 수 있어요.
        <br />
        내부 공백 · 특수문자 · 이모지는 사용할 수 없어요.
      </p>
      <button
        className={buttonStyles.primary}
        type="submit"
        disabled={busy || localError !== null || nickname.trim() === current}
      >
        {busy ? "저장 중…" : "저장"}
      </button>
    </form>
  );
}
