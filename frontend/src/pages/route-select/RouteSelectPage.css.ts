import { globalStyle, style } from "@vanilla-extract/css";

const ink = "#292A2D";
const mutedInk = "#66625E";
const coral = "#F47F73";
const border = "#E8D8D2";

export const page = style({
  position: "relative",
  isolation: "isolate",
  width: "100%",
  maxWidth: "425px",
  minHeight: "844px",
  margin: "0 auto",
  overflow: "hidden",
  borderRadius: "26px",
  backgroundColor: "#FFFFFF",

  selectors: {
    "&::before": {
      position: "absolute",
      zIndex: -1,
      top: "512px",
      left: "20px",
      width: "calc(100% - 40px)",
      height: "248px",
      backgroundColor: "#FFFDFC",
      content: "",
    },
  },

  "@media": {
    "screen and (max-width: 424px)": {
      minHeight: ["100vh", "100dvh"],
      borderRadius: 0,
    },
  },
});

export const header = style({
  position: "relative",
  zIndex: 1,
  width: "100%",
  height: "207px",
});

export const brandLockup = style({
  position: "absolute",
  top: "11px",
  left: "20px",
  width: "calc(100% - 40px)",
  height: "91px",
});

export const mascot = style({
  position: "absolute",
  top: 0,
  left: "-10px",
  width: "98px",
  height: "98px",
  objectFit: "contain",
});

export const wordmark = style({
  position: "absolute",
  top: "11px",
  left: "78px",
  width: "272px",
  height: "70px",
  objectFit: "contain",
});

export const intro = style({
  position: "absolute",
  top: "119px",
  left: "20px",
  width: "calc(100% - 40px)",
  height: "88px",
});

globalStyle(`${intro} > div`, {
  display: "flex",
  flexDirection: "column",
  gap: "8px",
});

globalStyle(`${intro} h1`, {
  display: "flex",
  alignItems: "center",
  height: "34px",
  margin: 0,
  color: ink,
  fontFamily: "'Nanum Gothic', 'Apple SD Gothic Neo', sans-serif",
  fontSize: "25px",
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
  position: "relative",
  zIndex: 1,
  width: "calc(100% - 40px)",
  margin: "17px 20px 0",
});

export const routeList = style({
  display: "flex",
  flexDirection: "column",
  gap: "12px",
  margin: 0,
  padding: 0,
  listStyle: "none",
});

export const routeItem = style({
  width: "100%",
  height: "128px",
});

export const routeButton = style({
  position: "relative",
  display: "block",
  width: "100%",
  height: "128px",
  margin: 0,
  padding: 0,
  overflow: "hidden",
  border: `1px solid ${border}`,
  borderRadius: "16px",
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
      top: "19px",
      left: "-1px",
      width: "4px",
      height: "36px",
      borderRadius: "0 3px 3px 0",
      backgroundColor: coral,
      content: "",
    },
    "&::after": {
      position: "absolute",
      right: "25px",
      bottom: "23px",
      display: "grid",
      placeItems: "center",
      width: "22px",
      height: "22px",
      color: coral,
      fontSize: "15px",
      fontWeight: 500,
      lineHeight: 1,
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
  position: "absolute",
  top: "7px",
  left: "19px",
  color: ink,
  fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
  fontSize: "52px",
  fontWeight: 800,
  lineHeight: 1.2,
  letterSpacing: "-2.5px",
});

export const routeDirection = style({
  position: "absolute",
  bottom: "23px",
  left: "19px",
  maxWidth: "calc(100% - 92px)",
  overflow: "hidden",
  color: mutedInk,
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  fontSize: "15px",
  fontWeight: 700,
  lineHeight: "22px",
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
});
