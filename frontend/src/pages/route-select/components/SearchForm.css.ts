import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const accent = "#E8523F";

export const form = style({
  display: "flex",
  alignItems: "center",
  height: "60px",
  paddingLeft: "22px",
  borderRadius: "30px",
  backgroundColor: vars.color.surface,
});

export const input = style({
  flex: 1,
  minWidth: 0,
  border: "none",
  outline: "none",
  background: "transparent",
  fontSize: "16px",
  color: vars.color.textStrong,
  "::placeholder": { color: vars.color.textSubtle },
});

export const searchIcon = style({
  flexShrink: 0,
  width: "22px",
  height: "22px",
  marginRight: "19px",
  fill: "none",
  stroke: accent,
  strokeWidth: 2,
  strokeLinecap: "round",
});

export const clearButton = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: "44px",
  height: "44px",
  marginRight: "8px",
  border: "none",
  background: "transparent",
  color: vars.color.iconMuted,
});

export const clearIcon = style({
  width: "18px",
  height: "18px",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2,
  strokeLinecap: "round",
});
