import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const notice = style({
  width: "100%",
  maxWidth: "254px",
  margin: "8px 0 0",
  padding: "10px",
  boxShadow: `inset 0 0 0 1px ${vars.color.forecastCardBorder}`,
  borderRadius: vars.radius.forecastCard,
  backgroundColor: vars.color.forecastCardSurface,
  color: vars.color.textBody,
  fontSize: vars.font.cardNotice,
  fontWeight: 500,
  lineHeight: 1.45,
  wordBreak: "keep-all",
});
