import { style } from "@vanilla-extract/css";

export const brand = style({
  display: "flex",
  width: "fit-content",
  alignItems: "center",
  gap: 6,
  color: "white",
  fontFamily: "'Fredoka', sans-serif",
  fontSize: 25,
  fontWeight: 700,
  letterSpacing: 0,
  textDecoration: "none",
  selectors: { "&:focus-visible": { outline: "2px solid white", outlineOffset: 4 } },
});

export const mascot = style({ width: 32, height: 36, objectFit: "contain" });
