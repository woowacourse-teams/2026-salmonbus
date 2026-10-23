import * as surface from "@/shared/styles/surface.css";
import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";
import { useFavorites } from "@/shared/hooks/useFavorites";
import { FavoritesNotice } from "@/shared/components/FavoritesNotice";
import { FavoriteEditor } from "@/shared/components/favorite-editor/FavoriteEditor";
import { Notice } from "@/shared/components/Notice";
import * as styles from "./GroupList.css";
import pencilIcon from "../assets/pencil.svg";

export function GroupList() {
  const { groups, resource, reload } = useFavorites();
  const [ordering, setOrdering] = useState(false);
  const [notice, showNotice] = useState(0);
  const editButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    return ordering ? () => editButton.current?.focus() : undefined;
  }, [ordering]);
  if (ordering)
    return (
      <FavoriteEditor
        onClose={(saved) => {
          setOrdering(false);
          if (saved) showNotice((value) => value + 1);
        }}
      />
    );
  return (
    <section className={`${styles.section} amp-mask`} aria-label="즐겨찾기 그룹 관리">
      <h2 className={styles.heading}>
        내 그룹 {groups.length} / 3{" "}
        <button
          ref={editButton}
          className={styles.edit}
          disabled={groups.length === 0}
          onClick={() => setOrdering(true)}
          aria-label="그룹과 즐겨찾기 순서 편집"
        >
          <img src={pencilIcon} width="20" height="20" alt="" draggable={false} />
        </button>
      </h2>
      <FavoritesNotice resource={resource} onRetry={reload} />
      <ul className={styles.list}>
        {groups.map((group) => (
          <li key={group.id}>
            <Link className={surface.row} to={`/mypage/groups/${encodeURIComponent(group.id)}`}>
              <span>
                {group.name}
                <span className={styles.count}>{group.favorites.length} / 2</span>
              </span>
              <span aria-hidden="true">›</span>
            </Link>
          </li>
        ))}
      </ul>
      {resource.status === "success" && groups.length < 3 && (
        <Link className={styles.add} to="/mypage/groups/new">
          ＋ 새 그룹 만들기
        </Link>
      )}
      {notice > 0 && <Notice key={notice} message="변경 사항을 저장했어요" />}
    </section>
  );
}
