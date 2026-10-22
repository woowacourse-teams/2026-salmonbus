import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { PHONE_MEDIA } from "./routeSelectLayout";
import bookmarkIcon from "@/features/favorites/bookmark.svg";

export const page = style({
  width: "100%",
  maxWidth: 425,
  minHeight: "100dvh",
  margin: "0 auto",
  paddingBottom: "calc(66px + env(safe-area-inset-bottom, 0px))",
  background: "#FAF9F7",
});

globalStyle(`html:has(${page})`, { "@media": { [PHONE_MEDIA]: { backgroundColor: vars.color.brand } } });

export const header = style({
  padding: "calc(18px + env(safe-area-inset-top, 0px)) 24px 16px",
  background: vars.color.brand,
});
export const searchArea = style({ marginTop: 12 });
export const account = style({ padding: "24px 32px", textAlign: "left" });

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
export const guestTitle = style({ margin: 0, color: "#292A2D", fontSize: 18, fontWeight: 700, lineHeight: "26px" });
export const guestDescription = style({
  width: "100%",
  margin: 0,
  color: "#76706A",
  fontSize: 14,
  lineHeight: 1.65,
  wordBreak: "keep-all",
});
export const guestLogin = style({
  selectors: { "&&": { borderRadius: 14, background: vars.color.liveBus, fontWeight: 700 } },
});
