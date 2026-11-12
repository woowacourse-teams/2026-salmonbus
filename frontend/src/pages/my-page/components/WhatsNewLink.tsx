import { Link } from "react-router";
import { paths } from "@/shared/routing/paths";
import { menuRow } from "./menuRow.css";

interface WhatsNewLinkProps {
  className?: string;
}

export function WhatsNewLink({ className }: WhatsNewLinkProps) {
  return (
    <Link className={`${menuRow} ${className ?? ""}`} to={paths.whatsNew} state={{ fromMyPage: true }}>
      새 소식<span aria-hidden="true">›</span>
    </Link>
  );
}
