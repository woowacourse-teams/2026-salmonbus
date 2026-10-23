import { MAX_FAVORITES_PER_GROUP } from "@/shared/utils/favoritePolicy";
import * as styles from "./EmptyFavoriteGroup.css";

export function EmptyFavoriteGroup() {
  return (
    <div className={styles.empty}>
      <p className={styles.emptyCount}>0 / {MAX_FAVORITES_PER_GROUP}</p>
      <p className={styles.emptyDescription}>아직 등록한 정류장이 없어요</p>
    </div>
  );
}
