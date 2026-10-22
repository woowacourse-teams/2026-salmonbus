import { style } from "@vanilla-extract/css";

export const brand = style({
  display: "flex",
  alignItems: "center",
  gap: 8,
  color: "white",
  fontSize: 25,
  fontWeight: 800,
  letterSpacing: "-0.5px",
});

export const mascot = style({ width: 32, height: 36, objectFit: "contain" });
