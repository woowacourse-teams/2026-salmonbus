import { Link } from "react-router";
import * as styles from "@/shared/styles/button.css";

export function LoginButton({ returnTo = "/", className }: { returnTo?: string; className?: string }) {
  return (
    <Link className={`${styles.primary} ${className ?? ""}`} to={`/login?${new URLSearchParams({ returnTo })}`}>
      로그인 / 회원가입
    </Link>
  );
}
