import { style } from "@vanilla-extract/css";

export const menuRow = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  width: "100%",
  minHeight: 48,
  padding: "8px 0",
  border: 0,
  background: "transparent",
  color: "inherit",
  font: "inherit",
  fontSize: 14,
  textAlign: "left",
  textDecoration: "none",
  cursor: "pointer",
});
