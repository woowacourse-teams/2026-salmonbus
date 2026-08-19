import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const header = style({
  display: "flex",
  alignItems: "center",
  height: "32px",
});

export const routeName = style({
  margin: 0,
  color: vars.color.textStrong,
  fontSize: "20px",
  fontWeight: 900,
});
