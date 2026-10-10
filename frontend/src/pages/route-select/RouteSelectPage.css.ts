import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const moveUp = "transform 400ms cubic-bezier(0.27, 1.05, 0.47, 0.97)";

export const page = style({
  position: "relative",
  width: "100%",
  maxWidth: "425px",
  minHeight: "844px",
  margin: "0 auto",
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
  padding: "20px 20px 0",
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

// 검색창 위 끝을 y 108(시안 B안)에 고정하고, 입력 전에는 검색창 중심이 화면 세로 가운데에 오도록 내려 둔다.
export const searchArea = style({
  position: "absolute",
  top: "108px",
  right: "20px",
  left: "20px",
  transform: "translateY(284px)",
  transition: moveUp,

  "@media": {
    "screen and (max-width: 424px)": {
      transform: ["translateY(calc(50vh - 138px))", "translateY(calc(50dvh - 138px))"],
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
