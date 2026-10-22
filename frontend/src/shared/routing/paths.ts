export const paths = {
  routeSelect: "/",
  board: "/routes/:routeId",
  login: "/login",
  mypage: "/mypage",
  nickname: "/mypage/nickname",
} as const;

export function boardPathFor(routeId: string): string {
  return `/routes/${encodeURIComponent(routeId)}`;
}
