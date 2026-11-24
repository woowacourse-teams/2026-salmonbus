import { useEffect, useRef, useState, type SubmitEvent } from "react";
import { renameFavoriteGroup } from "@/shared/api/favorites.api";
import type { FavoriteGroup } from "@/shared/api/favorites.types";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { groupNameError } from "@/shared/utils/favoritePolicy";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import * as styles from "./GroupNameForm.css";

interface GroupNameFormProps {
  group: FavoriteGroup;
  onRenamed: (name: string) => void;
  onCancel: () => void;
}

export function GroupNameForm({ group, onRenamed, onCancel }: GroupNameFormProps) {
  const field = useRef<HTMLInputElement>(null);
  const [name, setName] = useState(group.name);
  const { busy, error, clearError, run } = useFavoriteAction();
  const invalid = groupNameError(name);
  const unchanged = name.trim() === group.name;
  useEffect(() => {
    field.current?.focus();
  }, [error]);
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (invalid || unchanged) return;
    const renamed = await run(() => renameFavoriteGroup(group.id, name), { resultHaptic: true, kind: "renameGroup" });
    if (renamed) onRenamed(name.trim());
  }
  return (
    <form {...maskedPropsFor(styles.form)} onSubmit={(event) => void submit(event)}>
      <label className={styles.label} htmlFor="group-name">
        변경할 그룹 이름
      </label>
      <input
        ref={field}
        id="group-name"
        className={styles.field}
        value={name}
        autoComplete="off"
        aria-describedby="group-name-help group-name-error"
        aria-invalid={error !== null || (name !== "" && invalid !== null)}
        onChange={(event) => {
          setName(event.target.value);
          clearError();
        }}
      />
      <p id="group-name-help" className={styles.policy}>
        앞뒤 공백을 제외한 1~10자
        <br />
        특수문자와 이모지도 사용할 수 있어요.
        <br />
        같은 이름의 그룹은 만들 수 없어요.
      </p>
      <p
        id="group-name-error"
        className={formStyles.error}
        role="alert"
        hidden={error === null && (name === "" || invalid === null)}
      >
        {error ?? (name !== "" ? invalid : null)}
      </p>
      <div className={styles.renameActions}>
        <button type="button" className={buttons.secondary} disabled={busy} onClick={onCancel}>
          취소
        </button>
        <button className={styles.save} disabled={busy || invalid !== null || unchanged}>
          {busy ? "저장 중…" : "저장"}
        </button>
      </div>
    </form>
  );
}
