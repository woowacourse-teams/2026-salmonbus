export const paths = {
  routeSelect: "/",
  board: "/routes/:routeId",
  error: "/error",
} as const;

export function boardPathFor(routeId: string): string {
  return `/routes/${encodeURIComponent(routeId)}`;
}
