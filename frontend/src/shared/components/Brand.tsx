import type { MouseEventHandler } from "react";
import { Link } from "react-router";
import salmong from "@/shared/assets/images/salmong-logo/salmong-proud.png";
import { paths } from "@/shared/routing/paths";
import * as styles from "./Brand.css";

interface BrandProps {
  onClick?: MouseEventHandler<HTMLAnchorElement>;
  glass?: boolean;
}

export function Brand({ onClick, glass = false }: BrandProps) {
  return (
    <Link
      className={glass ? styles.glassBrand : styles.brand}
      to={paths.routeSelect}
      aria-label="연어버스 홈으로 이동"
      onClick={onClick}
    >
      <img className={glass ? styles.glassMascot : styles.mascot} src={salmong} alt="" />
      <span className={glass ? styles.glassWordmark : undefined}>연어버스</span>
    </Link>
  );
}
