export interface WhatsNewNote {
  id: number;
  version: string;
  publishedAt: string;
  features: readonly string[];
}

// 사용자에게 보이는 변경이 있는 배포마다 버전 PR에서 맨 위에 항목을 더한다.
// id는 앞 항목보다 1 크게, publishedAt은 배포한 날의 한국 날짜(YYYY-MM-DD)로 적는다.
export const WHATS_NEW_NOTES: readonly WhatsNewNote[] = [];
