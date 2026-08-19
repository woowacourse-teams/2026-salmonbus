import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const page = style({
  minHeight: ["100vh", "100dvh"],
  backgroundColor: vars.color.background,
});

export const content = style({
  display: "flex",
  flexDirection: "column",
  gap: "16px",
  maxWidth: "430px",
  margin: "0 auto",
  padding: "24px 18px 32px",
});
