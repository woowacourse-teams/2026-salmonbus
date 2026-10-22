import { globalStyle, style } from "@vanilla-extract/css";
import { PHONE_MEDIA } from "@/shared/styles/media";
import { vars } from "@/shared/styles/tokens.css";

const SAFE_AREA_TOP = "env(safe-area-inset-top, 0px)";
const SAFE_AREA_BOTTOM = "env(safe-area-inset-bottom, 0px)";

export const page = style({
  display: "flex",
  flexDirection: "column",
  width: "100%",
  maxWidth: "425px",
  height: "844px",
  margin: "0 auto",
  paddingBottom: SAFE_AREA_BOTTOM,
  overflow: "hidden",
  borderRadius: "26px",
  backgroundColor: vars.color.brand,

  "@media": {
    [PHONE_MEDIA]: {
      maxWidth: "none",
      height: ["100vh", "100dvh"],
      borderRadius: 0,
    },
  },
});

// iOS Safari는 상태 표시줄 아래 띠와 주소창 뒤를 html 배경색으로 칠한다. theme-color는 보지 않는다.
globalStyle(`html:has(${page})`, {
  "@media": {
    [PHONE_MEDIA]: {
      backgroundColor: vars.color.brand,
    },
  },
});

export const header = style({
  display: "flex",
  alignItems: "center",
  gap: "4px",
  padding: `calc(12px + ${SAFE_AREA_TOP}) 20px 0 8px`,
});

export const back = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: "44px",
  height: "44px",
  color: vars.color.onBrand,
  selectors: {
    "&:focus-visible": {
      outline: `2px solid ${vars.color.onBrand}`,
      outlineOffset: "-4px",
      borderRadius: vars.radius.pill,
    },
  },
});

export const backIcon = style({
  width: "24px",
  height: "24px",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 2.2,
  strokeLinecap: "round",
  strokeLinejoin: "round",
});

export const results = style({
  display: "flex",
  flex: 1,
  flexDirection: "column",
  minHeight: 0,
  padding: "4px 20px 16px",
});
