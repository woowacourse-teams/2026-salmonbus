import { useState, type SubmitEvent } from "react";
import { useNavigate } from "react-router";
import { createFavoriteGroup, renameFavoriteGroup } from "@/shared/api/favorites.api";
import type { FavoriteGroup } from "@/shared/api/favorites.types";
import { groupNameError } from "@/shared/utils/favoritePolicy";
import { useFavoriteAction } from "@/shared/hooks/useFavoriteAction";
import * as buttons from "@/shared/styles/button.css";
import * as formStyles from "@/shared/styles/form.css";

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
  const invalid = groupNameError(name);
  async function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    if (invalid || name.trim() === group?.name) return;
    if (await run(() => (group ? renameFavoriteGroup(group.id, name) : createFavoriteGroup(name)))) {
      if (onRenamed) onRenamed();
      else void navigate("/mypage", { replace: true, state: { notice: "그룹을 만들었어요" } });
    }
  }
  return (
    <form className="amp-mask" onSubmit={(event) => void submit(event)}>
      <label htmlFor="group-name">그룹 이름</label>
      <input
        id="group-name"
        className={formStyles.field}
        value={name}
        autoComplete="off"
        aria-describedby="group-name-help group-name-error"
        aria-invalid={error !== null || (name !== "" && invalid !== null)}
        onChange={(event) => {
          setName(event.target.value);
          setError(null);
        }}
      />
      <p id="group-name-help" className={formStyles.subtle}>
        앞뒤 공백을 제외한 1~10자
        <br />
        특수문자와 이모지도 사용할 수 있어요.
      </p>
      <p id="group-name-error" className={formStyles.error} role="alert">
        {error ?? (name !== "" ? invalid : null)}
      </p>
      <button className={buttons.primary} disabled={busy || invalid !== null || name.trim() === group?.name}>
        {busy ? "저장 중…" : group ? "저장" : "만들기"}
      </button>
    </form>
  );
}
