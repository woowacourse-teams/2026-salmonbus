import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import bookmarkIcon from "@/shared/assets/icons/bookmark.svg";

export const guestCard = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 15,
  width: "100%",
  maxWidth: 356,
  margin: "0 auto",
  padding: "24px 16px",
  borderRadius: 14,
  background: "white",
  boxShadow: "inset 0 0 0 1px #ECE8E3",
  fontFamily: "'Noto Sans KR', sans-serif",
  textAlign: "center",
});
export const guestIcon = style({
  flexShrink: 0,
  width: 30,
  height: 30,
  background: vars.color.liveBus,
  mask: `url(${bookmarkIcon}) center / contain no-repeat`,
});
export const guestTitle = style({
  margin: 0,
  color: "#292A2D",
  fontSize: 22,
  fontWeight: 700,
  lineHeight: "32px",
  wordBreak: "keep-all",
});
export const guestDescription = style({
  width: "100%",
  margin: 0,
  color: "#76706A",
  fontSize: 14,
  lineHeight: 1.6,
  wordBreak: "keep-all",
});
export const guestLogin = style({
  selectors: { "&&": { borderRadius: 14, background: vars.color.liveBus, fontWeight: 700 } },
});
