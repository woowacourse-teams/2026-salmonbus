import { style } from "@vanilla-extract/css";

export const heading = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  margin: "24px 0 12px",
  fontSize: 15,
  fontWeight: 600,
});
export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
