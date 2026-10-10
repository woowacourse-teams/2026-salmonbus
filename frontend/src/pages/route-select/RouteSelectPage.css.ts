import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const accent = "#E8523F";

export const page = style({
  width: "100%",
  maxWidth: "425px",
  minHeight: "844px",
  margin: "0 auto",
  overflow: "hidden",
  borderRadius: "26px",
  backgroundColor: accent,

  "@media": {
    "screen and (max-width: 424px)": {
      minHeight: ["100vh", "100dvh"],
      borderRadius: 0,
    },
  },
});

export const header = style({
  display: "flex",
  flexDirection: "column",
  gap: "14px",
  padding: "14px 20px 0",
});

export const brandLockup = style({
  display: "flex",
  alignItems: "center",
  gap: "2px",
});

export const mascot = style({
  width: "60px",
  height: "60px",
  objectFit: "contain",
});

export const wordmark = style({
  width: "175px",
  height: "45px",
  objectFit: "contain",
});

export const main = style({
  width: "calc(100% - 40px)",
  margin: "18px 20px 0",
});

export const question = style({
  margin: "0 0 16px",
  color: vars.color.onInk,
  fontFamily: "'Nanum Gothic', 'Apple SD Gothic Neo', sans-serif",
  fontSize: "22px",
  fontWeight: 800,
  lineHeight: 1.25,
  letterSpacing: "-0.6px",
});
