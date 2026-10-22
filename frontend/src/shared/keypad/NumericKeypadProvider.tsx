import { createContext, use, useCallback, useRef, type ReactNode } from "react";
import * as styles from "./NumericKeypadProvider.css";

const OpenNumericKeypadContext = createContext<() => void>(() => {});

// iOS Safari는 사용자가 탭하는 이벤트 안에서 focus()를 불러야 키패드를 띄운다.
// 화면을 옮기기 전에 이 input에 포커스를 줘서 숫자 키패드를 먼저 띄우고, 다음 화면의 입력창이 포커스를 넘겨받는다.
export function NumericKeypadProvider({ children }: { children: ReactNode }) {
  const holderRef = useRef<HTMLInputElement>(null);
  const openNumericKeypad = useCallback(() => holderRef.current?.focus({ preventScroll: true }), []);

  return (
    <OpenNumericKeypadContext value={openNumericKeypad}>
      {children}
      <input
        ref={holderRef}
        className={styles.holder}
        type="text"
        inputMode="numeric"
        tabIndex={-1}
        aria-hidden="true"
      />
    </OpenNumericKeypadContext>
  );
}

export function useOpenNumericKeypad() {
  return use(OpenNumericKeypadContext);
}
