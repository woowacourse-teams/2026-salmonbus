import { globalStyle, style } from "@vanilla-extract/css";
import { PHONE_MEDIA } from "@/shared/styles/media";
import { vars } from "@/shared/styles/tokens.css";
import { PHONE_SEARCH_CENTER_DVH } from "./routeSelectLayout";

const SAFE_AREA_TOP = "env(safe-area-inset-top, 0px)";
const SAFE_AREA_BOTTOM = "env(safe-area-inset-bottom, 0px)";

export const page = style({
  position: "relative",
  width: "100%",
  maxWidth: "425px",
  minHeight: "844px",
  margin: "0 auto",
  paddingBottom: SAFE_AREA_BOTTOM,
  overflow: "hidden",
  borderRadius: "26px",
  backgroundColor: vars.color.brand,

  "@media": {
    [PHONE_MEDIA]: {
      maxWidth: "none",
      minHeight: ["100vh", "100dvh"],
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
  padding: `calc(20px + ${SAFE_AREA_TOP}) 20px 0`,
});

export const brandLockup = style({
  display: "flex",
  alignItems: "center",
  gap: "6px",
});

export const mascot = style({
  width: "28px",
  height: "28px",
  objectFit: "contain",
});

export const wordmark = style({
  width: "auto",
  height: "24px",
  objectFit: "contain",
  filter: "brightness(0) invert(1)",
});

// 폰이 아니면 safe area 안의 세로 가운데에 둔다.
export const searchArea = style({
  position: "absolute",
  top: `calc(108px + ${SAFE_AREA_TOP})`,
  right: "20px",
  left: "20px",
  transform: `translateY(calc(284px - (${SAFE_AREA_TOP} + ${SAFE_AREA_BOTTOM}) / 2))`,

  "@media": {
    [PHONE_MEDIA]: {
      transform: [
        `translateY(calc(${PHONE_SEARCH_CENTER_DVH}vh - 138px - ${SAFE_AREA_TOP}))`,
        `translateY(calc(${PHONE_SEARCH_CENTER_DVH}dvh - 138px - ${SAFE_AREA_TOP}))`,
      ],
    },
  },
});

export const question = style({
  position: "absolute",
  right: 0,
  bottom: "100%",
  left: 0,
  margin: "0 0 18px",
  color: vars.color.onBrand,
  fontFamily: "'Nanum Gothic', 'Apple SD Gothic Neo', sans-serif",
  fontSize: "22px",
  fontWeight: 800,
  lineHeight: 1.25,
  letterSpacing: "-0.6px",
  textAlign: "center",
});

const PRESS = 0.98;

// 감쇠 스프링(누를 때 stiffness 1000, damping 55 / 뗄 때 800, 55)을 1/60초 간격으로 뽑은 곡선이다.
// 배열 앞쪽은 linear()를 모르는 브라우저가 쓰는 값이다.
const PRESS_IN = [
  "scale 200ms cubic-bezier(0.35, 0.4, 0.15, 1)",
  "scale 200ms linear(0, 0.103 8.3%, 0.304 16.7%, 0.508 25%, 0.677 33.3%, 0.802 41.7%, 0.887 50%, 0.941 58.3%, 0.973 66.7%, 0.991 75%, 0.999 83.3%, 1.003 91.7%, 1)",
];
const PRESS_OUT = [
  "scale 350ms cubic-bezier(0.35, 0.8, 0.05, 1)",
  "scale 350ms linear(0, 0.083 4.8%, 0.247 9.5%, 0.42 14.3%, 0.572 19%, 0.694 23.8%, 0.786 28.6%, 0.854 33.3%, 0.902 38.1%, 0.935 42.9%, 0.957 47.6%, 0.973 52.4%, 0.983 57.1%, 0.989 61.9%, 0.993 66.7%, 0.996 71.4%, 0.997 76.2%, 0.998 81%, 0.999 85.7%, 0.999 90.5%, 1 95.2%, 1)",
];

export const searchEntry = style({
  display: "flex",
  alignItems: "center",
  height: "60px",
  paddingLeft: "22px",
  borderRadius: "30px",
  backgroundColor: vars.color.surface,
  color: vars.color.textSubtle,
  fontSize: "16px",
  textDecoration: "none",
  // 평소에도 scale을 두어야 누르는 순간 stacking context가 새로 생기지 않는다.
  scale: "1",
  selectors: {
    "&:active": { scale: `${PRESS}` },
  },
  // 전환은 도착하는 상태의 값을 쓴다. 누를 때는 :active 쪽, 뗄 때는 기본 쪽 곡선이 돈다.
  "@media": {
    "(prefers-reduced-motion: no-preference)": {
      transition: PRESS_OUT,
      selectors: {
        "&:active": { transition: PRESS_IN },
      },
    },
  },
});

export const searchEntryText = style({
  flex: 1,
  minWidth: 0,
});

export const searchIcon = style({
  flexShrink: 0,
  width: "22px",
  height: "22px",
  marginRight: "19px",
  fill: "none",
  stroke: vars.color.brand,
  strokeWidth: 2,
  strokeLinecap: "round",
});
