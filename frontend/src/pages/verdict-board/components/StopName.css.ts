import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const nameBase = style({
  overflow: "hidden",
  maxWidth: "100%",
  lineHeight: vars.leading.stopName,
  whiteSpace: "nowrap",
  textOverflow: "ellipsis",
});

export const name = style([
  nameBase,
  {
    color: vars.color.textStrong,
    fontSize: vars.font.stopName,
    fontWeight: 600,
    letterSpacing: vars.tracking.stopName,
  },
]);

export const mutedName = style([
  nameBase,
  {
    color: vars.color.textMuted,
    fontSize: vars.font.stopNameCompact,
    fontWeight: 500,
  },
]);
