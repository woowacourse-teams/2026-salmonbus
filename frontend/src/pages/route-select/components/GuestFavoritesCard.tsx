import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import { LoginButton } from "@/shared/components/LoginButton";
import * as styles from "./GuestFavoritesCard.css";

export function GuestFavoritesCard() {
  return (
    <section className={styles.guestCard} aria-labelledby="guest-favorites-title">
      <span className={styles.mascotScene} aria-hidden="true">
        <svg className={styles.wake} viewBox="0 0 112 88" fill="none" focusable="false">
          <path
            d={styles.mascotOrbitPath}
            stroke="currentColor"
            strokeWidth="1.3"
            strokeLinecap="round"
            strokeDasharray="3 5"
          />
        </svg>
        <img className={styles.mascot} src={salmong} width={66} height={66} alt="" />
        <svg className={styles.orbitingStar} viewBox="0 0 24 24" focusable="false">
          <path
            d="m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-3-5.6 3 1.1-6.2L3 9.6l6.2-.9Z"
            fill="#FFD064"
            stroke="#B87928"
            strokeWidth="1.4"
            strokeLinejoin="round"
          />
        </svg>
      </span>
      <h2 id="guest-favorites-title" className={styles.guestTitle}>
        내 정류장을 저장해 보세요
      </h2>
      <p className={styles.guestDescription}>
        로그인을 하면 자주 타는 노선과 정류장을
        <br />내 그룹에 모아 볼 수 있어요.
      </p>
      <LoginButton returnTo="/favorites" className={styles.guestLogin} />
    </section>
  );
}
