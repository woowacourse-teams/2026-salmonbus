import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const dialog = style({
  selectors: {
    "&&": {
      maxWidth: 356,
      padding: "20px 18px",
      background: "white",
      boxShadow: "0 12px 36px #19151226",
      fontFamily: "'Noto Sans KR', sans-serif",
    },
  },
});

export const title = style({
  selectors: {
    "&&": {
      paddingRight: 42,
      margin: "0 0 16px",
      fontSize: 21,
      fontWeight: 700,
      lineHeight: 1.4,
      letterSpacing: "-0.6px",
      whiteSpace: "pre-line",
      wordBreak: "keep-all",
    },
  },
});

export const close = style({
  position: "absolute",
  top: 20,
  right: 18,
  display: "grid",
  placeItems: "center",
  width: 32,
  height: 32,
  padding: 0,
  border: 0,
  borderRadius: "50%",
  background: vars.color.liveBus,
  cursor: "pointer",
});

export const description = style({
  margin: "0 0 16px",
  color: "#837B74",
  fontSize: 14,
  lineHeight: 1.65,
  wordBreak: "keep-all",
});

export const login = style({
  selectors: {
    "&&": { borderRadius: 14, background: vars.color.liveBus, fontWeight: 700 },
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 4 },
  },
});
