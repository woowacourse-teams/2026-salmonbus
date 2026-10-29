import { Link } from "react-router";
import { paths } from "@/shared/routing/paths";
import * as styles from "./MyPageScreen.css";

interface WhatsNewLinkProps {
  className?: string;
}

export function WhatsNewLink({ className }: WhatsNewLinkProps) {
  return (
    <Link className={`${styles.row} ${className ?? ""}`} to={paths.whatsNew}>
      새 소식<span aria-hidden="true">›</span>
    </Link>
  );
}
