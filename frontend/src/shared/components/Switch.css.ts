import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const track = style({ position: "relative", flexShrink: 0, width: 46, height: 28 });

export const input = style({
  appearance: "none",
  position: "absolute",
  inset: 0,
  width: "100%",
  height: "100%",
  margin: 0,
  borderRadius: 14,
  background: "#D7D0C8",
  cursor: "pointer",
  transition: "background-color 150ms linear",
  selectors: {
    "&:checked": { background: vars.color.brand },
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 3 },
    "&:disabled": { opacity: 0.45, cursor: "default" },
  },
  "@media": {
    "(prefers-reduced-motion: reduce)": { transition: "none" },
    "(forced-colors: active)": {
      forcedColorAdjust: "none",
      border: "2px solid CanvasText",
      background: "Canvas",
      selectors: {
        "&:checked": { borderColor: "Highlight", background: "Highlight" },
        "&:focus-visible": { outlineColor: "CanvasText" },
      },
    },
  },
});

export const thumb = style({
  position: "absolute",
  top: 3,
  left: 3,
  width: 22,
  height: 22,
  borderRadius: "50%",
  background: "#FFFFFF",
  boxShadow: "0 1px 3px rgba(0, 0, 0, 0.2)",
  pointerEvents: "none",
  transition: "transform 150ms linear",
  selectors: { [`${input}:checked + &`]: { transform: "translateX(18px)" } },
  "@media": {
    "(prefers-reduced-motion: reduce)": { transition: "none" },
    "(forced-colors: active)": {
      forcedColorAdjust: "none",
      background: "CanvasText",
      boxShadow: "none",
      selectors: { [`${input}:checked + &`]: { background: "HighlightText" } },
    },
  },
});
