import { useEffect, useId, useRef, type ReactNode, type RefObject } from "react";
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
  const titleId = useId();
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
      aria-labelledby={titleId}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
    >
      <h2 id={titleId} className={`${styles.dialogTitle} ${titleClassName ?? ""}`}>
        {title}
      </h2>
      {children}
    </dialog>
  );
}
