import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const appear = keyframes({ to: { opacity: 1 } });

export const message = style({
  margin: 0,
  color: vars.color.textSubtle,
  fontSize: 14,
  opacity: 0,
  animation: `${appear} 0s linear 200ms forwards`,
});

export const app = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  minHeight: "100dvh",
  background: vars.color.background,
});

export const sheet = style({
  display: "flex",
  justifyContent: "center",
  padding: "32px 16px",
});
