import { style } from "@vanilla-extract/css";

export const star = style({
  display: "grid",
  placeItems: "center",
  width: 32,
  height: 32,
  padding: 0,
  border: 0,
  borderRadius: "50%",
  background: "transparent",
  cursor: "pointer",
});
