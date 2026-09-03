import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const BADGE_WIDTH = "154px";
const BADGE_HEIGHT = "24px";

const badgeBase = style({
  overflow: "hidden",
  width: BADGE_WIDTH,
  minWidth: 0,
  height: BADGE_HEIGHT,
  padding: "0 12px",
  borderRadius: vars.radius.seatBadge,
  fontSize: vars.font.seat,
  fontWeight: 600,
  lineHeight: BADGE_HEIGHT,
  whiteSpace: "nowrap",
  textAlign: "center",
  textOverflow: "ellipsis",
});

export const badge = styleVariants(vars.color.seat, (tone) => [
  badgeBase,
  {
    backgroundColor: tone.surface,
    color: tone.text,
  },
]);
