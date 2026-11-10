import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const page = style({
  position: "relative",
  background: vars.color.surface,
});

export const header = style({
  position: "absolute",
  top: "calc(12px + env(safe-area-inset-top, 0px))",
  left: "calc(12px + env(safe-area-inset-left, 0px))",
});

export const back = style({
  width: 44,
  height: 44,
  border: 0,
  background: "transparent",
  color: vars.color.textStrong,
  font: "inherit",
  fontSize: 24,
  cursor: "pointer",
  ":focus-visible": {
    outline: `3px solid ${vars.color.focusRing}`,
    outlineOffset: 4,
  },
});

export const content = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  minHeight: "100dvh",
  padding:
    "calc(24px + env(safe-area-inset-top, 0px)) calc(24px + env(safe-area-inset-right, 0px)) calc(24px + env(safe-area-inset-bottom, 0px)) calc(24px + env(safe-area-inset-left, 0px))",
  textAlign: "center",
});

export const mascot = style({
  width: 96,
  height: 96,
  objectFit: "contain",
  flexShrink: 0,
  marginBottom: 20,
});

export const title = style({
  margin: 0,
  color: vars.color.textStrong,
  fontSize: 20,
  fontWeight: 700,
  lineHeight: "24px",
  overflowWrap: "anywhere",
});

export const caption = style({
  margin: "8px 0 0",
  color: vars.color.textSubtle,
  fontSize: 15,
  lineHeight: "20px",
  overflowWrap: "anywhere",
});

export const retry = style({
  marginTop: 28,
  padding: "14px 24px",
  maxWidth: "100%",
  border: 0,
  borderRadius: 14,
  background: vars.color.ink,
  color: vars.color.onInk,
  font: "inherit",
  fontSize: 16,
  fontWeight: 600,
  lineHeight: "20px",
  cursor: "pointer",
  textDecoration: "none",
  ":active": { transform: "scale(0.97)" },
  ":focus-visible": {
    outline: `3px solid ${vars.color.focusRing}`,
    outlineOffset: 4,
  },
});
