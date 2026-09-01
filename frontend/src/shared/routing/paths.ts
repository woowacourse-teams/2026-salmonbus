export const paths = {
  routeSelect: "/",
  board: "/routes/:routeId",
} as const;

export function boardPathFor(routeId: string): string {
  return `/routes/${encodeURIComponent(routeId)}`;
}
