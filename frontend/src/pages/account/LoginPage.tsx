import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router";
import { useAuth, localReturnTo, boardReturnTo, type LoginProvider } from "@/features/auth";
import { Brand, ConfirmDialog } from "@/features/auth/AccountLayout";
import { clearLoginReturn, saveLoginReturn } from "@/features/auth/loginReturn";
import * as styles from "@/features/auth/account.css";

declare global {
  interface Window {
    ReactNativeWebView?: { postMessage: (message: string) => void };
    __SALMONBUS_NATIVE_AUTH__?: boolean;
  }
}

export function LoginPage() {
  const { state, reload } = useAuth();
  const [query, setQuery] = useSearchParams();
  const navigate = useNavigate();
  const returnTo = localReturnTo(query.get("returnTo"));
  const [pending, setPending] = useState(false);
  const [nativeError, setNativeError] = useState<string | null>(null);
  const [loginResult, setLoginResult] = useState(() => query.get("login"));
  const failed = loginResult === "failed";
  const native = Boolean(window.__SALMONBUS_NATIVE_AUTH__);

  useEffect(() => {
    if (!query.has("login")) return;
    setQuery(
      (previous) => {
        const next = new URLSearchParams(previous);
        next.delete("login");
        return next;
      },
      { replace: true },
    );
  }, [query, setQuery]);

  useEffect(() => {
    const onNative = (event: Event) => {
      const detail: unknown = (event as CustomEvent).detail;
      if (typeof detail !== "object" || detail === null || !("status" in detail)) return;
      setPending(false);
      if (detail.status === "cancelled") return;
      if (detail.status === "failed") setNativeError("카카오 로그인을 완료하지 못했어요. 다시 시도해 주세요.");
      else if (detail.status === "providerAuthenticated")
        setNativeError("카카오 인증은 완료됐어요. 연어버스 로그인 연결은 준비 중이에요.");
      else setNativeError("앱 로그인을 준비 중이에요.");
    };
    window.addEventListener("salmonbus:auth", onNative);
    const onPageShow = () => setPending(false);
    window.addEventListener("pageshow", onPageShow);
    return () => {
      window.removeEventListener("salmonbus:auth", onNative);
      window.removeEventListener("pageshow", onPageShow);
    };
  }, []);

  function login(provider: LoginProvider) {
    if (pending) return;
    setNativeError(null);
    setLoginResult(null);
    if (native && provider === "APPLE") {
      setNativeError("앱의 Apple 로그인을 준비 중이에요.");
      return;
    }
    if (provider === "KAKAO" && window.__SALMONBUS_NATIVE_AUTH__ && window.ReactNativeWebView) {
      setPending(true);
      window.ReactNativeWebView.postMessage(JSON.stringify({ type: "auth.login", provider: "KAKAO" }));
      return;
    }
    saveLoginReturn(returnTo, provider);
    setPending(true);
    window.location.assign(
      `/api/auth/${provider.toLowerCase()}/start?${new URLSearchParams({ returnTo: boardReturnTo(returnTo) })}`,
    );
  }

  function dismissFailure() {
    setLoginResult(null);
  }

  return (
    <main className={styles.login}>
      <Brand />
      <h1 className={styles.greeting}>
        기다림은
        <br />
        짧게.
        <span className={styles.greetingGap}>
          출발은
          <br />
          가볍게.
        </span>
      </h1>
      <p className={styles.loginCaption}>
        내가 탈 버스, 앉아 갈 수 있을까?
        <br />
        로그인하고 Salmonbus를 이용해 보세요.
      </p>
      <div className={styles.loginActions}>
        <button
          type="button"
          className={styles.kakao}
          disabled={
            pending ||
            state.status === "loading" ||
            (!native && (state.status === "unavailable" || state.status === "error"))
          }
          onClick={() => login("KAKAO")}
        >
          <svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 3C6.5 3 2 6.5 2 10.8c0 2.8 1.9 5.3 4.8 6.7L6 21l4-2.4h2c5.5 0 10-3.5 10-7.8S17.5 3 12 3Z" />
          </svg>
          카카오로 계속하기
        </button>
        <button
          type="button"
          className={styles.apple}
          disabled={pending || state.status === "loading" || state.status === "unavailable" || state.status === "error"}
          onClick={() => login("APPLE")}
        >
          <span aria-hidden="true" style={{ fontSize: 23 }}>
            
          </span>
          Apple로 계속하기
        </button>
        <Link className={styles.back} to={returnTo} onClick={() => clearLoginReturn()}>
          돌아가기
        </Link>
        {pending && (
          <p className={styles.loginNotice} role="status">
            로그인 화면을 열고 있어요…
          </p>
        )}
        {state.status === "loading" && (
          <p className={styles.loginNotice} role="status">
            로그인 상태를 확인하고 있어요…
          </p>
        )}
        {state.status === "unavailable" && (
          <p className={styles.loginNotice} role="status">
            로그인 기능을 준비 중이에요. 노선 검색은 계속 이용할 수 있어요.
          </p>
        )}
        {state.status === "error" && (
          <>
            <p className={styles.loginNotice} role="alert">
              로그인 상태를 확인하지 못했어요.
            </p>
            <button type="button" className={styles.back} onClick={() => void reload()}>
              다시 확인하기
            </button>
          </>
        )}
        {loginResult === "cancelled" && (
          <p className={styles.loginNotice} role="status">
            로그인을 취소했어요.
          </p>
        )}
        {nativeError && (
          <p className={styles.loginNotice} role="status">
            {nativeError}
          </p>
        )}
      </div>
      {failed && (
        <ConfirmDialog title="로그인을 완료하지 못했어요" onCancel={dismissFailure}>
          <p className={styles.caption}>다시 로그인하거나 돌아가서 노선을 검색할 수 있어요.</p>
          <div className={styles.actions}>
            <button className={styles.primary} type="button" onClick={dismissFailure}>
              다시 로그인
            </button>
            <button
              className={styles.secondary}
              type="button"
              onClick={() => {
                clearLoginReturn();
                void navigate(returnTo, { replace: true });
              }}
            >
              돌아가기
            </button>
          </div>
        </ConfirmDialog>
      )}
    </main>
  );
}
