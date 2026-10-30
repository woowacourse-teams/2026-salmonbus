import { globalStyle, style } from "@vanilla-extract/css";
import { POINTER_INPUT } from "@/shared/input-method/inputMethod";
import { vars } from "@/shared/styles/tokens.css";

export const dialog = style({
  border: 0,
  borderRadius: 14,
  padding: 24,
  width: "calc(100% - 64px)",
  maxWidth: 360,
  color: "#292A2D",
  background: "#FAF9F7",
  selectors: { "&::backdrop": { background: "#0006" } },
});

export const dialogTitle = style({ fontSize: 18, margin: "0 0 12px" });

globalStyle(`${dialog} button:focus-visible`, { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 4 });
globalStyle(`${POINTER_INPUT} ${dialog} button:focus`, { outline: "none" });
