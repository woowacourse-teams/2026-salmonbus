import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const header = style({
  display: "flex",
  alignItems: "center",
  gap: "10px",
  height: "32px",
});

export const back = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  flexShrink: 0,
  width: "32px",
  height: "32px",
  borderRadius: vars.radius.pill,
  backgroundColor: vars.color.ink,
  color: vars.color.onInk,
  selectors: {
    "&:focus-visible": {
      outline: `2px solid ${vars.color.focusRing}`,
      outlineOffset: "2px",
    },
  },
});

export const backIcon = style({
  display: "block",
});

export const routeName = style({
  margin: 0,
  color: vars.color.ink,
  fontSize: vars.font.routeName,
  fontWeight: 600,
  letterSpacing: "-0.6px",
});
