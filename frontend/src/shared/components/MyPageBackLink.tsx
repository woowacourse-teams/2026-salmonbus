import { Link, useLocation, useNavigate } from "react-router";
import { paths } from "@/shared/routing/paths";

interface MyPageBackLinkProps {
  className: string;
}

export function MyPageBackLink({ className }: MyPageBackLinkProps) {
  const location = useLocation();
  const navigate = useNavigate();
  const isFirstPage = location.key === "default";

  return (
    <Link
      className={className}
      to={paths.mypage}
      aria-label="마이페이지로 돌아가기"
      replace
      onClick={(event) => {
        if (isFirstPage || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)
          return;
        event.preventDefault();
        void navigate(-1);
      }}
    >
      <svg
        width="17"
        height="17"
        viewBox="0 0 17 17"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M13.6 8.5H3.4M7.9 4 3.4 8.5 7.9 13" />
      </svg>
    </Link>
  );
}
