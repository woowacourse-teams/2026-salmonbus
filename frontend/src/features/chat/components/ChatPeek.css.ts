import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { DESKTOP_MEDIA, MOTION_MEDIA } from "../chatLayout";

const SLOT_RIGHT = "18px";
const SLOT_HEIGHT = "44px";
const PEEK_WIDTH = "168px";
const PEEK_HEIGHT = "36px";
const PEEK_RADIUS = "18px";
const PEEK_SHADOW = "0 4px 16px rgba(17, 17, 17, 0.12)";
const PEEK_FONT = "600 13px/1.3 -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif";
const ICON_SIZE = "15px";
const BADGE_SIZE = "17px";
const BADGE_RADIUS = "9px";

export const slot = style({
  position: "absolute",
  right: SLOT_RIGHT,
  bottom: "calc(env(safe-area-inset-bottom, 0px) + 4px)",
  display: "flex",
  height: SLOT_HEIGHT,
  alignItems: "flex-start",
  pointerEvents: "auto",
  "@media": {
    [DESKTOP_MEDIA]: { right: 0, bottom: 0 },
  },
});

export const launcher = style({
  display: "flex",
  alignItems: "center",
  width: PEEK_WIDTH,
  height: PEEK_HEIGHT,
  gap: "5px",
  margin: 0,
  padding: "0 10px 0 12px",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: PEEK_RADIUS,
  background: vars.color.surface,
  boxShadow: PEEK_SHADOW,
  color: vars.color.textStrong,
  font: PEEK_FONT,
  cursor: "pointer",
  WebkitTapHighlightColor: "transparent",
  selectors: {
    "&:hover": { borderColor: vars.color.iconMuted, transform: "translateY(-1px)" },
    "&:active": { transform: "translateY(0)" },
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: "2px" },
  },
  "@media": {
    [MOTION_MEDIA]: {
      transition: `transform ${vars.duration.fast}, border-color ${vars.duration.fast}`,
    },
  },
});

export const icon = style({
  width: ICON_SIZE,
  height: ICON_SIZE,
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.9,
});

export const route = style({ fontVariantNumeric: "tabular-nums" });

export const spacer = style({ flex: 1 });

export const unreadBadge = style({
  display: "grid",
  minWidth: BADGE_SIZE,
  height: BADGE_SIZE,
  padding: "0 5px",
  placeItems: "center",
  borderRadius: BADGE_RADIUS,
  background: vars.color.ink,
  color: vars.color.onInk,
  fontSize: "11px",
  fontWeight: 700,
});
