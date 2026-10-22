import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { useAuth, nicknameErrorOf, authErrorOf } from "@/features/auth";
import { AccountLayout, LoginButton } from "@/features/auth/AccountLayout";
import * as styles from "@/features/auth/account.css";

export function NicknamePage() {
  const { state } = useAuth();
  return (
    <AccountLayout>
      <div className={styles.backTitle}>
        <Link className={styles.backIcon} to="/mypage" aria-label="마이페이지로 돌아가기">
          ‹
        </Link>
        <h1 className={styles.title} style={{ margin: 0 }}>
          닉네임 수정
        </h1>
      </div>
      {state.status === "authenticated" ? (
        <NicknameForm key={state.member.id} current={state.member.nickname} />
      ) : state.status === "loading" ? (
        <p role="status">로그인 상태를 확인하고 있어요…</p>
      ) : (
        <LoginButton returnTo="/mypage" />
      )}
    </AccountLayout>
  );
}

function NicknameForm({ current }: { current: string }) {
  const active = useRef(false);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
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
    const result = await changeNickname(nickname);
    if (!active.current) return;
    setBusy(false);
    if (result.ok) {
      void navigate("/mypage", { replace: true, state: { notice: "닉네임을 변경했어요" } });
    } else setError(authErrorOf(result.failure));
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
        className={styles.primary}
        type="submit"
        disabled={busy || localError !== null || nickname.trim() === current || error !== null}
      >
        {busy ? "저장 중…" : "저장"}
      </button>
    </form>
  );
}
