import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import * as styles from "./Brand.css";

export function Brand() {
  return (
    <span className={styles.brand}>
      <img className={styles.mascot} src={salmong} alt="연어 버스 로고" />
      <span>Salmonbus</span>
    </span>
  );
}
