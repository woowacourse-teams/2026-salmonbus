import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const form = style({
  display: "flex",
  flex: 1,
  alignItems: "center",
  minWidth: 0,
  height: "44px",
  paddingLeft: "16px",
  borderRadius: "12px",
  backgroundColor: vars.color.searchFieldSurface,
});

export const searchIcon = style({
  flexShrink: 0,
  width: "20px",
  height: "20px",
  marginRight: "12px",
  fill: "none",
  stroke: vars.color.iconMuted,
  strokeWidth: 2,
  strokeLinecap: "round",
});

export const input = style({
  flex: 1,
  minWidth: 0,
  height: "100%",
  border: "none",
  outline: "none",
  background: "transparent",
  fontSize: "16px",
  color: vars.color.textStrong,
  caretColor: vars.color.brand,
  "::placeholder": { color: vars.color.faint },
});

export const clearButton = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: "44px",
  height: "44px",
  border: "none",
  background: "transparent",
  color: vars.color.faint,
});

export const clearIcon = style({
  width: "20px",
  height: "20px",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.6,
  strokeLinecap: "round",
});
