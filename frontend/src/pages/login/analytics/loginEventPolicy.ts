import type { EventMap } from "@/shared/analytics/events";

type LoginEntry = EventMap["login_started"]["entry"];

// 로그인 버튼마다 돌아갈 곳이 달라서 returnTo로 어디서 로그인을 시작했는지 안다.
export function loginEntryOf(returnTo: string): LoginEntry {
  if (returnTo.startsWith("/routes/")) return "favorite";
  if (returnTo === "/mypage") return "mypage";
  return "home";
}
