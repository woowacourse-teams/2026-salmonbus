import { createContext, use } from "react";

// 포커스가 끊기지 않고 다음 화면의 입력창으로 이어지게 넘어간다. 그래서 떠 있는 키보드도 유지된다. 주소의 경로(pathname)와 history state만 받는다.
export type NavigateKeepingFocus = (pathname: string, state?: unknown) => void;

export const FocusKeepingNavigationContext = createContext<NavigateKeepingFocus | null>(null);

export function useNavigateKeepingFocus(): NavigateKeepingFocus {
  const navigateKeepingFocus = use(FocusKeepingNavigationContext);
  if (navigateKeepingFocus === null) throw new Error("AppRoutes 안에서 사용해야 합니다.");
  return navigateKeepingFocus;
}
