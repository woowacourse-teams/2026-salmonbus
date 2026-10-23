import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const primary = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 10,
  width: "100%",
  minHeight: 48,
  padding: "12px 18px",
  border: 0,
  borderRadius: 8,
  background: vars.color.brand,
  color: "white",
  font: "inherit",
  fontSize: 14,
  fontWeight: 600,
  textDecoration: "none",
  cursor: "pointer",
  selectors: { "&:disabled": { background: "#E8E4E0", color: "#76716B", cursor: "default" } },
});

export const secondary = style([primary, { background: "#F0ECE7", color: "#59554F" }]);

export const text = style({
  padding: 6,
  border: 0,
  borderRadius: 4,
  background: "transparent",
  color: vars.color.textSubtle,
  font: "inherit",
  fontSize: 13,
  cursor: "pointer",
  selectors: { "&:disabled": { opacity: 0.45, cursor: "default" } },
});
