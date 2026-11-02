import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const row = style({
  flexBasis: "100%",
  display: "flex",
  flexDirection: "column",
  marginTop: 4,
  paddingTop: 10,
  borderTop: "1px solid #F1EEEA",
});
export const line = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  minHeight: 32,
});
export const summary = style({
  minWidth: 0,
  minHeight: 32,
  padding: 0,
  border: 0,
  background: "transparent",
  color: "#292A2D",
  font: "inherit",
  fontSize: 14,
  fontWeight: 600,
  textAlign: "left",
  textDecoration: "underline",
  textDecorationColor: "#D7D0C8",
  textUnderlineOffset: 4,
  cursor: "pointer",
  selectors: { "&:disabled": { opacity: 0.45, cursor: "default" } },
});
export const offLabel = style({ color: "#837B74", fontSize: 14 });
export const notice = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  marginTop: 8,
});
export const noticeText = style({ margin: 0, color: "#68645F", fontSize: 12, lineHeight: 1.6, wordBreak: "keep-all" });
export const settingsButton = style({
  flexShrink: 0,
  height: 32,
  padding: "0 12px",
  border: 0,
  borderRadius: 8,
  background: "#F1EEEA",
  color: "#292A2D",
  font: "inherit",
  fontSize: 12,
  fontWeight: 600,
  cursor: "pointer",
});

export const sheet = style({
  selectors: {
    "&&": {
      width: "100%",
      maxWidth: "100%",
      margin: "auto 0 0",
      borderRadius: "20px 20px 0 0",
      padding: "24px 20px calc(20px + env(safe-area-inset-bottom))",
      background: "#FFFFFF",
      fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
    },
    "&&::backdrop": { background: "#19151266" },
  },
});
export const field = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  marginTop: 18,
  fontSize: 14,
  fontWeight: 600,
});
export const time = style({
  minWidth: 120,
  height: 44,
  padding: "0 12px",
  border: "1px solid #EAE6E0",
  borderRadius: 10,
  background: "#FAF9F7",
  color: "#292A2D",
  font: "inherit",
  fontSize: 16,
});
export const weekdays = style({ display: "flex", gap: 6, marginTop: 16 });
export const weekday = style({
  flex: 1,
  minWidth: 0,
  height: 40,
  padding: 0,
  border: 0,
  borderRadius: 10,
  background: "#F1EEEA",
  color: "#76706A",
  font: "inherit",
  fontSize: 14,
  fontWeight: 600,
  cursor: "pointer",
  selectors: {
    '&[aria-pressed="true"]': { background: vars.color.brand, color: "#FFFFFF" },
    "&:disabled": { opacity: 0.45, cursor: "default" },
  },
});
