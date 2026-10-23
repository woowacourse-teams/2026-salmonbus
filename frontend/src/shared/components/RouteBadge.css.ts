import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const badge = style({
  flexShrink: 0,
  padding: "3px 7px",
  borderRadius: 6,
  background: "#FFF0EB",
  color: vars.color.liveBus,
  fontSize: 11,
  fontWeight: 700,
  lineHeight: "16px",
});
