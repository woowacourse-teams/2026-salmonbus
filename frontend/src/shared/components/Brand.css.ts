import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const brand = style({
  display: "flex",
  width: "fit-content",
  alignItems: "center",
  gap: 2,
  color: "white",
  fontFamily: "'Do Hyeon', sans-serif",
  fontSize: 25,
  fontWeight: 400,
  letterSpacing: 0,
  textDecoration: "none",
  selectors: { "&:focus-visible": { outline: "2px solid white", outlineOffset: 4 } },
});

export const mascot = style({ width: 32, height: 36, objectFit: "contain" });

export const glassBrand = style([
  brand,
  {
    gap: 3,
    color: vars.color.ink,
    fontSize: 21,
    lineHeight: 1,
    whiteSpace: "nowrap",
    selectors: { "&:focus-visible": { outlineColor: "#8C3C30" } },
    "@media": {
      "(max-width: 399px)": { fontSize: 20 },
      "(max-width: 389px)": { fontSize: 19 },
    },
  },
]);

export const glassMascot = style([
  mascot,
  {
    width: 27,
    height: 31,
    "@media": {
      "(max-width: 399px)": { width: 24, height: 28 },
      "(max-width: 389px)": { width: 21, height: 25 },
    },
  },
]);
export const glassWordmark = style({ translate: "0 1px" });
