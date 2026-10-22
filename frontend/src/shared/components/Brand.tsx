import type { MouseEventHandler } from "react";
import { Link } from "react-router";
import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import { paths } from "@/shared/routing/paths";
import * as styles from "./Brand.css";

export function Brand({ onClick }: { onClick?: MouseEventHandler<HTMLAnchorElement> }) {
  return (
    <Link className={styles.brand} to={paths.routeSelect} aria-label="Salmonbus 홈으로 이동" onClick={onClick}>
      <img className={styles.mascot} src={salmong} alt="연어 버스 로고" />
      <span>Salmonbus</span>
    </Link>
  );
}
