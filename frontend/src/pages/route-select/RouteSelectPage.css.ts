import { globalStyle, keyframes, style } from "@vanilla-extract/css";

const ink = "#292A2D";
const mutedInk = "#66625E";
const coral = "#F47F73";
const border = "#E8D8D2";
const lightInk = "#B9B2AC";

export const page = style({
  width: "100%",
  maxWidth: "425px",
  minHeight: "844px",
  margin: "0 auto",
  overflow: "hidden",
  borderRadius: "26px",
  backgroundColor: "#FFFFFF",

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

// 스프링(감쇠비 0.595, 응답 617ms)으로 8px를 한 번 튀게 계산한 값
const mascotHop = keyframes({
  "0%": { translate: "0 0" },
  "1.7%": { translate: "0 -1.83px" },
  "3.3%": { translate: "0 -3.38px" },
  "5%": { translate: "0 -4.67px" },
  "6.7%": { translate: "0 -5.72px" },
  "8.3%": { translate: "0 -6.55px" },
  "10%": { translate: "0 -7.17px" },
  "11.7%": { translate: "0 -7.6px" },
  "15%": { translate: "0 -7.99px" },
  "18.3%": { translate: "0 -7.86px" },
  "23.3%": { translate: "0 -6.99px" },
  "33.3%": { translate: "0 -4.19px" },
  "41.7%": { translate: "0 -1.95px" },
  "48.3%": { translate: "0 -0.62px" },
  "55%": { translate: "0 0.23px" },
  "63.3%": { translate: "0 0.72px" },
  "75%": { translate: "0 0.71px" },
  "100%": { translate: "0 0" },
});

export const hoppingMascot = style([
  mascot,
  {
    animation: `${mascotHop} 727ms linear 300ms`,

    "@media": {
      "(prefers-reduced-motion: reduce)": {
        animation: "none",
      },
    },
  },
]);

export const wordmark = style({
  width: "175px",
  height: "45px",
  objectFit: "contain",
});

export const intro = style({});

globalStyle(`${intro} > div`, {
  display: "flex",
  flexDirection: "column",
  gap: "6px",
});

globalStyle(`${intro} h1`, {
  margin: 0,
  color: ink,
  fontFamily: "'Nanum Gothic', 'Apple SD Gothic Neo', sans-serif",
  fontSize: "22px",
  fontWeight: 800,
  lineHeight: 1.25,
  letterSpacing: "-0.6px",
});

globalStyle(`${intro} p`, {
  margin: 0,
  color: mutedInk,
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  fontSize: "13px",
  fontWeight: 500,
  lineHeight: 1.55,
  whiteSpace: "pre-line",
});

export const main = style({
  width: "calc(100% - 40px)",
  margin: "18px 20px 0",
});

const revealAfterDelay = keyframes({
  from: { visibility: "hidden" },
});

export const loadingMessage = style({
  animation: `${revealAfterDelay} 0s 200ms both`,
});

export const routeList = style({
  display: "flex",
  flexDirection: "column",
  gap: "8px",
  margin: 0,
  padding: 0,
  listStyle: "none",
});

export const routeItem = style({
  width: "100%",
  height: "70px",
});

export const routeButton = style({
  position: "relative",
  display: "flex",
  alignItems: "center",
  gap: "14px",
  width: "100%",
  height: "70px",
  margin: 0,
  padding: "0 17px 0 18px",
  overflow: "hidden",
  border: `1px solid ${border}`,
  borderRadius: "14px",
  backgroundColor: "#FFFFFF",
  color: ink,
  fontFamily: "inherit",
  textAlign: "left",
  cursor: "pointer",
  appearance: "none",
  WebkitTapHighlightColor: "transparent",

  selectors: {
    "&::before": {
      position: "absolute",
      top: "20px",
      left: "-1px",
      width: "4px",
      height: "28px",
      borderRadius: "0 3px 3px 0",
      backgroundColor: coral,
      content: "",
    },
    "&::after": {
      flexShrink: 0,
      color: coral,
      fontSize: "15px",
      fontWeight: 500,
      lineHeight: 1.2,
      content: "↗",
    },
    "&:hover": {
      borderColor: "#DCC9C2",
      backgroundColor: "#FFF8F5",
    },
    "&:active": {
      backgroundColor: "#FFF8F5",
    },
    "&:focus-visible": {
      outline: `2px solid ${coral}`,
      outlineOffset: "2px",
    },
  },
});

export const routeNumber = style({
  flexShrink: 0,
  minWidth: "94px",
  color: ink,
  fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
  fontSize: "36px",
  fontWeight: 800,
  lineHeight: 1.2,
  letterSpacing: "-1.7px",
});

export const routeDirection = style({
  display: "flex",
  flex: 1,
  alignItems: "center",
  gap: "8px",
  minWidth: 0,
});

export const routeLine = style({
  display: "flex",
  flexDirection: "column",
  justifyContent: "space-between",
  flexShrink: 0,
  width: "7px",
  height: "27px",
  background: `linear-gradient(${border}, ${border}) center / 1.5px calc(100% - 14px) no-repeat`,

  selectors: {
    "&::before": {
      width: "7px",
      height: "7px",
      border: `1.5px solid ${lightInk}`,
      borderRadius: "50%",
      content: "",
    },
    "&::after": {
      width: "7px",
      height: "7px",
      borderRadius: "50%",
      backgroundColor: coral,
      content: "",
    },
  },
});

export const stopNames = style({
  display: "flex",
  flex: 1,
  flexDirection: "column",
  gap: "3px",
  minWidth: 0,
});

const stopName = style({
  overflow: "hidden",
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  lineHeight: 1.3,
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
});

export const startStopName = style([
  stopName,
  {
    color: mutedInk,
    fontSize: "12.5px",
    fontWeight: 500,
  },
]);

export const endStopName = style([
  stopName,
  {
    color: ink,
    fontSize: "13.5px",
    fontWeight: 700,
  },
]);
