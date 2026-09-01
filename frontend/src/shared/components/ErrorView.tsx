import { PageInfo } from "./PageInfo";
import salmongCrying from "../assets/images/salmong-logo/salmong-crying.png";

interface ErrorViewProps {
  title: string;
  caption: string;
  onBack?: () => void;
  onRetry?: () => void;
}

export function ErrorView({ title, caption, onBack, onRetry }: ErrorViewProps) {
  return (
    <>
      <header>
        {onBack && (
          <button type="button" onClick={onBack} aria-label="이전 페이지로 돌아가는 뒤로가기 버튼">
            ←
          </button>
        )}
      </header>
      <main>
        <img src={salmongCrying} alt="연어 버스 로고" />
        <PageInfo title={title} caption={caption} />
        {onRetry && (
          <button type="button" onClick={onRetry}>
            다시 시도하기
          </button>
        )}
      </main>
    </>
  );
}
