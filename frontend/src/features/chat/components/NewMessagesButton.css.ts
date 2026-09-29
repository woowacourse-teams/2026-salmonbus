import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const BOTTOM_OFFSET = "82px";
const BUTTON_HEIGHT = "32px";
const BUTTON_RADIUS = "16px";
const BUTTON_SHADOW = "0 4px 16px rgba(17, 17, 17, 0.10)";
const ICON_SIZE = "14px";

export const button = style({
  position: "absolute",
  right: "50%",
  bottom: BOTTOM_OFFSET,
  display: "flex",
  height: BUTTON_HEIGHT,
  alignItems: "center",
  gap: "4px",
  padding: "0 12px 0 10px",
  border: 0,
  borderRadius: BUTTON_RADIUS,
  background: vars.color.ink,
  boxShadow: BUTTON_SHADOW,
  color: vars.color.onInk,
  fontSize: "13px",
  fontWeight: 600,
  transform: "translateX(50%)",
  cursor: "pointer",
});

export const icon = style({
  width: ICON_SIZE,
  height: ICON_SIZE,
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round",
  strokeLinejoin: "round",
});
