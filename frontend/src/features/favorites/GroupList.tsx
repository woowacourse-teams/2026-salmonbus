import { useState } from "react";
import { Link } from "react-router";
import { useFavorites } from "./FavoritesContext";
import { FavoritesNotice } from "./FavoritesNotice";
import { OrderDialog } from "./OrderDialog";
import * as styles from "./favorites.css";

export function GroupList() {
  const { groups, resource, reload } = useFavorites();
  const [ordering, setOrdering] = useState(false);
  return (
    <section className={`${styles.section} amp-mask`} aria-label="즐겨찾기 그룹 관리">
      <h2 className={styles.heading}>
        내 그룹 {groups.length} / 3{" "}
        <button
          className={styles.action}
          disabled={groups.length === 0}
          onClick={() => setOrdering(true)}
          aria-label="그룹과 즐겨찾기 순서 편집"
        >
          ✎
        </button>
      </h2>
      <FavoritesNotice resource={resource} onRetry={reload} />
      <ul className={styles.list}>
        {groups.map((group) => (
          <li key={group.id}>
            <Link className={styles.groupRow} to={`/mypage/groups/${encodeURIComponent(group.id)}`}>
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
      {ordering && <OrderDialog onClose={() => setOrdering(false)} />}
    </section>
  );
}
