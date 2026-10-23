import { LoginButton } from "@/shared/components/LoginButton";
import guestProfile from "../assets/guest-profile.svg";
import * as styles from "./GuestMyPage.css";

export function GuestMyPage() {
  return (
    <div className={styles.page}>
      <h1 className={styles.title}>마이페이지</h1>
      <section className={styles.welcome} aria-labelledby="guest-mypage-title">
        <div className={styles.profile}>
          <img src={guestProfile} width="34" height="34" alt="" />
        </div>
        <h2 id="guest-mypage-title" className={styles.headline}>
          연어버스에 합류 하세요!
        </h2>
        <p className={styles.description}>
          자주 타는 노선부터 출퇴근 그룹까지,
          <br />
          로그인하고 나만의 버스 생활을 시작해요.
        </p>
      </section>
      <LoginButton returnTo="/mypage" className={styles.login} />
    </div>
  );
}
