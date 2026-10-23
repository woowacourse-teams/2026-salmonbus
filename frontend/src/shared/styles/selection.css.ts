import { style } from "@vanilla-extract/css";
import { vars } from "./tokens.css";

export const pill = style({
  border: 0,
  borderRadius: 20,
  padding: "8px 18px",
  background: "#EEECE7",
  color: vars.color.textBody,
  fontFamily: "-apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
  fontSize: 13,
  fontWeight: 400,
  lineHeight: "normal",
});
