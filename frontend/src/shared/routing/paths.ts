export const paths = {
  routeSelect: "/",
  routeSearch: "/search",
  board: "/routes/:routeId",
} as const;

export function boardPathFor(routeId: string): string {
  return `/routes/${encodeURIComponent(routeId)}`;
}
