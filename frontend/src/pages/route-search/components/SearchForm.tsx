import { useRef } from "react";
import { CLEAR_INPUT_LABEL, SEARCH_INPUT_LABEL, SEARCH_PLACEHOLDER } from "../routeSearchCopy";
import * as styles from "./SearchForm.css";

interface SearchFormProps {
  value: string;
  autoFocus?: boolean;
  onChange: (value: string) => void;
  onClear: () => void;
  onSubmit: () => void;
}

export function SearchForm({ value, autoFocus = false, onChange, onClear, onSubmit }: SearchFormProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  // 지우기 버튼은 입력이 비면 사라져서, 키보드로 누르면 포커스가 갈 곳을 잃는다.
  const handleClear = () => {
    onClear();
    inputRef.current?.focus();
  };

  return (
    <form
      className={styles.form}
      role="search"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      <input
        ref={inputRef}
        className={styles.input}
        type="text"
        // 홈 검색바(route-select)가 띄운 숫자 키패드를 그대로 이어받는다. 바꾸면 홈 검색바도 함께 바꾼다.
        inputMode="numeric"
        autoComplete="off"
        autoFocus={autoFocus}
        aria-label={SEARCH_INPUT_LABEL}
        placeholder={SEARCH_PLACEHOLDER}
        value={value}
        onChange={(event) => onChange(event.target.value)}
      />
      {value === "" ? (
        <svg className={styles.searchIcon} viewBox="0 0 24 24" aria-hidden="true">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
      ) : (
        <button
          className={styles.clearButton}
          type="button"
          aria-label={CLEAR_INPUT_LABEL}
          onPointerDown={(event) => event.preventDefault()}
          onClick={handleClear}
        >
          <svg className={styles.clearIcon} viewBox="0 0 24 24" aria-hidden="true">
            <circle cx="12" cy="12" r="8" />
            <path d="m9.5 9.5 5 5m0-5-5 5" />
          </svg>
        </button>
      )}
    </form>
  );
}
