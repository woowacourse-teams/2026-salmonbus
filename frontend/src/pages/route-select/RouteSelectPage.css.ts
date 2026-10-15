import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const moveUp = "transform 400ms cubic-bezier(0.27, 1.05, 0.47, 0.97)";
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
    "screen and (max-width: 424px)": {
      minHeight: ["100vh", "100dvh"],
      borderRadius: 0,
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

// 검색 중에는 safe area 아래 108px에 두고, 입력 전에는 safe area 안의 세로 가운데에 둔다.
export const searchArea = style({
  position: "absolute",
  top: `calc(108px + ${SAFE_AREA_TOP})`,
  right: "20px",
  left: "20px",
  transform: `translateY(calc(284px - (${SAFE_AREA_TOP} + ${SAFE_AREA_BOTTOM}) / 2))`,
  transition: moveUp,

  "@media": {
    "screen and (max-width: 424px)": {
      transform: [
        `translateY(calc(50vh - 138px - (${SAFE_AREA_TOP} + ${SAFE_AREA_BOTTOM}) / 2))`,
        `translateY(calc(50dvh - 138px - (${SAFE_AREA_TOP} + ${SAFE_AREA_BOTTOM}) / 2))`,
      ],
    },
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
    },
  },

  // :has를 모르는 브라우저가 [data-filled]까지 버리지 않도록 두 선택자를 따로 둔다.
  selectors: {
    "&:has(input:focus)": {
      transform: "none",
    },
    "&[data-filled]": {
      transform: "none",
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
  transition: "opacity 150ms linear",

  "@media": {
    "(prefers-reduced-motion: reduce)": {
      transition: "none",
    },
  },

  selectors: {
    [`${searchArea}:has(input:focus) &`]: {
      opacity: 0,
      pointerEvents: "none",
    },
    [`${searchArea}[data-filled] &`]: {
      opacity: 0,
      pointerEvents: "none",
    },
  },
});
