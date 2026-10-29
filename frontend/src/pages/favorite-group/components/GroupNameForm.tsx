import { useState, type SubmitEvent } from "react";
import { useNavigate } from "react-router";
import { createFavoriteGroup, renameFavoriteGroup } from "@/shared/api/favorites.api";
import type { FavoriteGroup } from "@/shared/api/favorites.types";
import { maskedPropsFor } from "@/shared/analytics/maskedProps";
import { groupNameError } from "@/shared/utils/favoritePolicy";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import { useStillOnScreen } from "@/shared/routing/stillOnScreen";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";
import * as styles from "./GroupNameForm.css";

interface CreateGroupNameFormProps {
  group?: never;
  onRenamed?: never;
}

interface RenameGroupNameFormProps {
  group: FavoriteGroup;
  onRenamed: () => void;
}

type GroupNameFormProps = CreateGroupNameFormProps | RenameGroupNameFormProps;

export function GroupNameForm({ group, onRenamed }: GroupNameFormProps) {
  const [name, setName] = useState(group?.name ?? "");
  const { busy, error, setError, run } = useFavoriteAction();
  const navigate = useNavigate();
  const isStillOnScreen = useStillOnScreen();
  const invalid = groupNameError(name);
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (invalid || name.trim() === group?.name) return;
    if (await run(() => (group ? renameFavoriteGroup(group.id, name) : createFavoriteGroup(name)))) {
      if (onRenamed) onRenamed();
      else if (isStillOnScreen()) void navigate("/mypage", { replace: true, state: { notice: "그룹을 만들었어요" } });
    }
  }
  return (
    <form {...maskedPropsFor(group ? styles.form : undefined)} onSubmit={(event) => void submit(event)}>
      <label className={group ? styles.label : undefined} htmlFor="group-name">
        {group ? "변경할 그룹 이름" : "그룹 이름"}
      </label>
      <input
        id="group-name"
        className={group ? styles.field : formStyles.field}
        value={name}
        autoComplete="off"
        aria-describedby="group-name-help group-name-error"
        aria-invalid={error !== null || (name !== "" && invalid !== null)}
        onChange={(event) => {
          setName(event.target.value);
          setError(null);
        }}
      />
      <p id="group-name-help" className={group ? styles.policy : formStyles.subtle}>
        앞뒤 공백을 제외한 1~10자
        <br />
        특수문자와 이모지도 사용할 수 있어요.
        {group && (
          <>
            <br />
            같은 이름의 그룹은 만들 수 없어요.
          </>
        )}
      </p>
      <p
        id="group-name-error"
        className={formStyles.error}
        role="alert"
        hidden={group !== undefined && error === null && (name === "" || invalid === null)}
      >
        {error ?? (name !== "" ? invalid : null)}
      </p>
      <button
        className={group ? styles.save : buttons.primary}
        disabled={busy || invalid !== null || name.trim() === group?.name}
      >
        {busy ? "저장 중…" : group ? "저장" : "만들기"}
      </button>
    </form>
  );
}
