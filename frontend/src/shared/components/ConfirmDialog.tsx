import { useEffect, useRef, type ReactNode, type RefObject } from "react";
import * as styles from "./ConfirmDialog.css";

interface ConfirmDialogProps {
  title: string;
  children: ReactNode;
  onCancel: () => void;
  className?: string;
  titleClassName?: string;
  fallbackFocus?: RefObject<HTMLElement | null>;
}

export function ConfirmDialog({
  title,
  children,
  onCancel,
  className,
  titleClassName,
  fallbackFocus,
}: ConfirmDialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const previouslyFocused = document.activeElement;
    const fallbackTarget = fallbackFocus?.current;
    dialog?.showModal();
    return () => {
      dialog?.close();
      const target =
        previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected ? previouslyFocused : fallbackTarget;
      target?.focus();
    };
  }, [fallbackFocus]);
  return (
    <dialog
      ref={ref}
      className={`${styles.dialog} ${className ?? ""}`}
      aria-labelledby="account-dialog-title"
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
    >
      {/* showModal은 포커스할 수 있는 첫 요소에 포커스를 준다. 제목이 포커스를 받게 해서 키보드를 쓰지 않은 사람에게 버튼 포커스 링이 보이지 않게 한다. */}
      <h2 id="account-dialog-title" className={`${styles.dialogTitle} ${titleClassName ?? ""}`} tabIndex={-1}>
        {title}
      </h2>
      {children}
    </dialog>
  );
}
