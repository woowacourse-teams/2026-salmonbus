import { LoginButton } from "@/shared/components/LoginButton";
import starOutline from "@/shared/assets/icons/star-outline.svg";
import * as styles from "./GuestFavoritesCard.css";

export function GuestFavoritesCard() {
  return (
    <section className={styles.guestCard} aria-labelledby="guest-favorites-title">
      <img src={starOutline} width="30" height="30" alt="" draggable={false} />
      <h2 id="guest-favorites-title" className={styles.guestTitle}>
        내 정류장을 저장해 보세요
      </h2>
      <p className={styles.guestDescription}>
        로그인을 하면 자주 타는 노선과 정류장을
        <br />내 그룹에 모아 볼 수 있어요.
      </p>
      <LoginButton returnTo="/" className={styles.guestLogin} />
    </section>
  );
}
