import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const shell = style({
  display: "flex",
  flexDirection: "column",
  width: "100%",
  maxWidth: 425,
  minHeight: "100dvh",
  margin: "0 auto",
  background: "#FAF9F7",
  color: "#292A2D",
  paddingBottom: "calc(66px + env(safe-area-inset-bottom, 0px))",
});

export const top = style({ height: "env(safe-area-inset-top, 0px)", background: vars.color.brand, flexShrink: 0 });

export const content = style({ padding: "24px 32px", flex: 1 });

globalStyle(`${shell} button:focus-visible, ${shell} a:focus-visible`, {
  outline: `3px solid ${vars.color.focusRing}`,
  outlineOffset: 4,
});
