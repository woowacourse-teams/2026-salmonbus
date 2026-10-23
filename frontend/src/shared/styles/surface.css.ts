import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const card = style({
  display: "block",
  padding: "16px",
  border: "1px solid #E8E4DF",
  borderRadius: 12,
  background: vars.color.surface,
  color: vars.color.textStrong,
  textDecoration: "none",
});
export const row = style([
  card,
  { display: "flex", justifyContent: "space-between", alignItems: "center", minHeight: 66 },
]);

export const outlinedCard = style({
  border: 0,
  outline: "1px solid #EAE6E0",
  outlineOffset: "-0.5px",
  borderRadius: 14,
  background: vars.color.surface,
});
