import { Link } from "react-router";
import * as styles from "@/shared/styles/button.css";

interface LoginButtonProps {
  returnTo?: string;
  className?: string;
}

export function LoginButton({ returnTo = "/", className }: LoginButtonProps) {
  return (
    <Link className={`${styles.primary} ${className ?? ""}`} to={`/login?${new URLSearchParams({ returnTo })}`}>
      로그인 / 회원가입
    </Link>
  );
}
