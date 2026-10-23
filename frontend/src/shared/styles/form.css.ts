import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const subtle = style({ margin: "8px 0", color: vars.color.textSubtle, fontSize: 12, lineHeight: 1.6 });
export const error = style({ margin: "12px 0", color: "#BA3529", fontSize: 13, lineHeight: 1.6 });
export const actions = style({ display: "flex", gap: 10, marginTop: 20 });
export const field = style({
  display: "block",
  width: "100%",
  padding: "14px",
  margin: "12px 0",
  minHeight: 48,
  border: "1px solid #E8E4DF",
  borderRadius: 12,
  background: "white",
  font: "inherit",
  fontSize: 16,
});
