import { useEffect, useId, useRef, type ReactNode, type RefObject } from "react";
import * as styles from "./ConfirmDialog.css";

interface ConfirmDialogProps {
  title: string;
  children: ReactNode;
  onCancel: () => void;
  className?: string;
  titleClassName?: string;
  fallbackFocus?: RefObject<HTMLElement | null>;
  initialFocus?: RefObject<HTMLElement | null>;
}

const FOCUSABLE = "button, input, select, textarea, a[href], [tabindex]:not([tabindex='-1'])";
const INPUT_TYPES_WITHOUT_KEYBOARD = new Set(["checkbox", "radio", "button", "submit", "reset", "range"]);

function opensKeyboardOrPicker(element: HTMLElement): boolean {
  if (element instanceof HTMLTextAreaElement || element instanceof HTMLSelectElement) return true;
  return element instanceof HTMLInputElement && !INPUT_TYPES_WITHOUT_KEYBOARD.has(element.type);
}

function firstFocusOf(dialog: HTMLDialogElement, title: HTMLElement): HTMLElement {
  const firstControl = [...dialog.querySelectorAll<HTMLElement>(FOCUSABLE)].find(
    (element) => !element.matches(":disabled"),
  );
  return firstControl === undefined || opensKeyboardOrPicker(firstControl) ? title : firstControl;
}

export function ConfirmDialog({
  title,
  children,
  onCancel,
  className,
  titleClassName,
  fallbackFocus,
  initialFocus,
}: ConfirmDialogProps) {
  const titleId = useId();
  const ref = useRef<HTMLDialogElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const dialog = ref.current;
    const previouslyFocused = document.activeElement;
    const fallbackTarget = fallbackFocus?.current;
    const title = titleRef.current;
    if (!dialog || !title) return;

    const focusTarget = initialFocus?.current ?? firstFocusOf(dialog, title);
    const hadAutofocus = focusTarget.hasAttribute("autofocus");
    focusTarget.setAttribute("autofocus", "");
    try {
      dialog.showModal();
    } finally {
      if (!hadAutofocus) focusTarget.removeAttribute("autofocus");
    }
    if (document.activeElement !== focusTarget) focusTarget.focus();
    return () => {
      dialog.close();
      const target =
        previouslyFocused instanceof HTMLElement && previouslyFocused.isConnected ? previouslyFocused : fallbackTarget;
      target?.focus();
    };
  }, [fallbackFocus, initialFocus]);
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
      <h2 ref={titleRef} id={titleId} tabIndex={-1} className={`${styles.dialogTitle} ${titleClassName ?? ""}`}>
        {title}
      </h2>
      {children}
    </dialog>
  );
}
