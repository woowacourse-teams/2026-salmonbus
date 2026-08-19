import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const board = style({
  overflow: "hidden",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: vars.radius.card,
  backgroundColor: vars.color.surface,
});

export const message = style({
  margin: 0,
  padding: "48px 0",
  color: vars.color.textMuted,
  fontSize: "13px",
  textAlign: "center",
});
