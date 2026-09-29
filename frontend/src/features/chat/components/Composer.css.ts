import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const SAFE_AREA_BOTTOM = "env(safe-area-inset-bottom, 0px)";
const CONTROL_SIZE = "44px";
const CONTROL_RADIUS = "14px";
const TEXTAREA_MAX_HEIGHT = "104px";
const ICON_SIZE = "20px";
const COUNT_RIGHT = "69px";

export const composer = style({
  position: "relative",
  flexShrink: 0,
  padding: `8px 12px calc(8px + ${SAFE_AREA_BOTTOM})`,
  borderTop: `1px solid ${vars.color.rowDivider}`,
  background: vars.color.surface,
});

export const hint = style({
  margin: "0 4px 6px",
  color: vars.color.textBody,
  fontSize: "11px",
  fontWeight: 600,
});

export const row = style({ display: "flex", alignItems: "flex-end", gap: "8px" });

export const textarea = style({
  width: "100%",
  minHeight: CONTROL_SIZE,
  maxHeight: TEXTAREA_MAX_HEIGHT,
  resize: "none",
  padding: "11px 42px 11px 13px",
  overflowY: "auto",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: CONTROL_RADIUS,
  background: vars.color.forecastCardSurface,
  color: vars.color.textStrong,
  fontFamily: "inherit",
  fontSize: "16px",
  fontWeight: 500,
  lineHeight: "22px",
  selectors: {
    "&::placeholder": { color: vars.color.textSubtle },
    "&:focus": { borderColor: vars.color.ink, outline: "none", background: vars.color.surface },
    "&:focus-visible": { boxShadow: `0 0 0 2px ${vars.color.focusRing}` },
  },
});

export const sendButton = style({
  display: "grid",
  flexShrink: 0,
  width: CONTROL_SIZE,
  height: CONTROL_SIZE,
  padding: 0,
  border: 0,
  borderRadius: CONTROL_RADIUS,
  placeItems: "center",
  background: vars.color.ink,
  color: vars.color.onInk,
  cursor: "pointer",
  selectors: {
    '&[aria-disabled="true"]': { background: vars.color.mutedChipSurface, color: vars.color.faint, cursor: "default" },
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: "2px" },
  },
});

export const sendIcon = style({
  width: ICON_SIZE,
  height: ICON_SIZE,
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round",
  strokeLinejoin: "round",
});

const characterCountBase = style({
  position: "absolute",
  right: COUNT_RIGHT,
  bottom: `calc(11px + ${SAFE_AREA_BOTTOM})`,
  color: vars.color.textMuted,
  fontSize: "9.5px",
  opacity: 0,
  pointerEvents: "none",
});

export const characterCount = styleVariants({
  hidden: [characterCountBase],
  visible: [characterCountBase, { opacity: 1 }],
});
