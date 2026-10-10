import { useRef } from "react";
import { CLEAR_INPUT_LABEL, SEARCH_INPUT_LABEL, SEARCH_PLACEHOLDER } from "../routeSelectCopy";
import * as styles from "./SearchForm.css";

interface SearchFormProps {
  value: string;
  onChange: (value: string) => void;
  onClear: () => void;
  onSubmit: () => void;
}

export function SearchForm({ value, onChange, onClear, onSubmit }: SearchFormProps) {
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
        inputMode="numeric"
        autoComplete="off"
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
            <path d="M7 7l10 10M17 7 7 17" />
          </svg>
        </button>
      )}
    </form>
  );
}
