import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { PHONE_MEDIA } from "./routeSelectLayout";

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
export const account = style({ padding: "32px", textAlign: "center" });
export const welcome = style({ margin: "116px 0 0", color: "#68645F", fontSize: 14, lineHeight: 1.6 });
