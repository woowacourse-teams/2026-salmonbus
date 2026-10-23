import { LoginButton } from "@/shared/components/LoginButton";
import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import * as styles from "./GuestMyPage.css";

export function GuestMyPage() {
  return (
    <div className={styles.page}>
      <h1 className={styles.title}>마이페이지</h1>
      <section className={styles.welcome} aria-labelledby="guest-mypage-title">
        <img className={styles.profile} src={salmong} width="64" height="64" alt="살몽이" />
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
