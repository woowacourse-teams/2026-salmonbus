import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const mapControls = style({
  position: "absolute",
  display: "grid",
  gap: 8,
  pointerEvents: "none",
});

export const mapButton = style({
  display: "grid",
  placeItems: "center",
  width: 42,
  height: 42,
  padding: 0,
  border: `1px solid ${vars.color.rowDivider}`,
  borderRadius: 21,
  background: vars.color.surface,
  color: vars.color.ink,
  boxShadow: "0 2px 8px #00000015",
  cursor: "pointer",
  pointerEvents: "auto",
  selectors: {
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 2 },
    "&:disabled": { opacity: 0.65, cursor: "wait" },
  },
});
