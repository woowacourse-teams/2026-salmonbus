import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const name = style({
  overflow: "hidden",
  color: vars.color.textStrong,
  fontSize: vars.font.label,
  fontWeight: 700,
  whiteSpace: "nowrap",
  textOverflow: "ellipsis",
});

export const mutedName = style([
  name,
  {
    color: vars.color.textMuted,
    fontSize: "12px",
    fontWeight: 500,
  },
]);
