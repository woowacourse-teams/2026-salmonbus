import type { FavoriteTarget } from "@/shared/api/favorites.types";
import { ConfirmDialog } from "@/shared/components/ConfirmDialog";
import { LoginButton } from "@/shared/components/LoginButton";
import { useAuth } from "@/shared/hooks/useAuth";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import { favoritePath } from "@/shared/routing/paths";
import closeIcon from "../assets/login-close.svg";
import * as promptStyles from "./LoginPrompt.css";

interface LoginPromptProps {
  target: FavoriteTarget;
  onClose: () => void;
}

export function LoginPrompt({ target, onClose }: LoginPromptProps) {
  const { state, reload } = useAuth();
  return (
    <ConfirmDialog
      title={"로그인하면 즐겨찾기를\n등록할 수 있어요"}
      className={promptStyles.dialog}
      titleClassName={promptStyles.title}
      onCancel={onClose}
    >
      <button className={promptStyles.close} type="button" aria-label="닫기" onClick={onClose}>
        <img src={closeIcon} width="17" height="17" alt="" draggable={false} />
      </button>
      <p className={promptStyles.description}>
        로그인을 하면 자주 타는 노선과 정류장을
        <br />내 그룹에 모아 볼 수 있어요.
      </p>
      {state.status === "anonymous" ? (
        <LoginButton returnTo={favoritePath(target)} className={promptStyles.login} />
      ) : (
        <>
          <p className={formStyles.subtle} role="status">
            {state.status === "loading"
              ? "로그인 상태를 확인하고 있어요…"
              : state.status === "unavailable"
                ? "로그인 기능을 준비 중이에요."
                : "로그인 상태를 확인하지 못했어요."}
          </p>
          {state.status === "error" && (
            <button className={buttons.secondary} onClick={() => void reload()}>
              다시 확인하기
            </button>
          )}
        </>
      )}
    </ConfirmDialog>
  );
}
