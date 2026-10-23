import { style } from "@vanilla-extract/css";
import { primary } from "@/shared/styles/button.css";

export const form = style({ display: "flex", flexDirection: "column" });
export const label = style({ color: "#76706A", fontSize: 13, fontWeight: 600, lineHeight: "19px", marginBottom: 9 });
export const field = style({
  width: "100%",
  height: 56,
  padding: "0 16px",
  border: "1px solid #D8CEC5",
  borderRadius: 14,
  background: "#FFFFFF",
  color: "#292A2D",
  font: "inherit",
  fontSize: 17,
  fontWeight: 500,
});
export const policy = style({ margin: "16px 0", color: "#837B74", fontSize: 13, lineHeight: 1.7 });
export const save = style([
  primary,
  { minHeight: 50, height: 50, padding: 0, borderRadius: 14, background: "#E8533F", fontSize: 15, fontWeight: 700 },
]);
