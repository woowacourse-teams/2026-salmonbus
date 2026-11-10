import { Link } from "react-router";
import * as styles from "@/shared/styles/button.css";

interface LoginButtonProps {
  returnTo?: string;
  className?: string;
  menuRowClassName?: string;
}

export function LoginButton({ returnTo = "/", className, menuRowClassName }: LoginButtonProps) {
  return (
    <Link
      className={menuRowClassName ?? `${styles.primary} ${className ?? ""}`}
      to={`/login?${new URLSearchParams({ returnTo })}`}
    >
      로그인 / 회원가입
      {menuRowClassName && <span aria-hidden="true">›</span>}
    </Link>
  );
}
