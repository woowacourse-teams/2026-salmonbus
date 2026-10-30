import type { MouseEventHandler } from "react";
import { Link } from "react-router";
import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import { paths } from "@/shared/routing/paths";
import * as styles from "./Brand.css";

interface BrandProps {
  onClick?: MouseEventHandler<HTMLAnchorElement>;
}

export function Brand({ onClick }: BrandProps) {
  return (
    <Link className={styles.brand} to={paths.routeSelect} aria-label="연어버스 홈으로 이동" onClick={onClick}>
      <img className={styles.mascot} src={salmong} alt="" />
      <span>연어버스</span>
    </Link>
  );
}
