import { globalStyle, style } from "@vanilla-extract/css";
import { activeRouteColor, inactiveRouteColor } from "@/shared/map/routeColors";
import { stopPinGlyph, stopPinLabel } from "@/shared/map/mapStopPin.css";
import { vars } from "@/shared/styles/tokens.css";
import { canvas } from "./RouteMap.css";

export const stop = style({
  selectors: {
    "&&": { width: 24, height: 24 },
    [`${canvas}[data-overlays-ready="false"] &`]: { visibility: "hidden" },
    '&&[aria-pressed="true"]': { width: 32, height: 32 },
  },
});

globalStyle(`${stop}[data-boarding-allowed="false"] ${stopPinGlyph}`, {
  borderColor: "#C5C9CF",
  background: "#F4F5F6",
  color: inactiveRouteColor,
  boxShadow: "0 1px 3px #5B647224",
});

globalStyle(`${stop}[data-boarding-allowed="false"][aria-pressed="true"] ${stopPinGlyph}`, {
  borderColor: "#FFFFFF",
  background: inactiveRouteColor,
  color: vars.color.onInk,
  boxShadow: "0 0 0 3px #C5C9CF, 0 2px 6px #5B647245",
});

globalStyle(`${stop} ${stopPinLabel}`, {
  padding: 1,
  border: 0,
  background: "transparent",
  boxShadow: "none",
  color: vars.color.onInk,
  fontSize: 13,
  fontWeight: 800,
  WebkitTextStroke: "2px #000000",
  paintOrder: "stroke fill",
});

export const stopConnector = style({
  position: "absolute",
  top: "50%",
  left: "50%",
  overflow: "visible",
  pointerEvents: "none",
  stroke: activeRouteColor,
  strokeWidth: 1.5,
  strokeOpacity: 0.65,
  strokeLinecap: "round",
});
export const bus = style({
  position: "relative",
  display: "block",
  width: 40,
  height: 52,
  padding: 0,
  border: 0,
  outline: "none",
  background: "transparent",
  color: vars.color.onInk,
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  fontSize: 11,
  fontWeight: 700,
  whiteSpace: "nowrap",
  pointerEvents: "auto",
  cursor: "pointer",
  transformOrigin: "50% 100%",
  transition: `transform ${vars.duration.fast} ${vars.easing.slide}`,
  "@media": {
    "(prefers-reduced-motion: reduce)": { transition: "none" },
  },
  selectors: {
    [`${canvas}[data-overlays-ready="false"] &`]: { visibility: "hidden" },
    '&[aria-pressed="true"]': { transform: "scale(1.5)" },
    "&:focus-visible": { transform: "scale(1.5)" },
  },
});
export const busCount = style({
  position: "absolute",
  top: 0,
  left: "50%",
  translate: "-50% 0",
  display: "grid",
  placeItems: "center",
  width: "max-content",
  minWidth: 32,
  height: 18,
  padding: "0 6px",
  lineHeight: 1,
  borderRadius: 5,
  background: vars.color.mapBus,
  selectors: {
    "&[hidden]": { display: "none" },
  },
});
export const busPin = style({
  position: "absolute",
  top: 20,
  left: 7,
  width: 26,
  height: 32,
  color: vars.color.mapBus,
});
export const busIcon = style({
  position: "absolute",
  top: 5,
  left: 5,
  display: "block",
  width: 16,
  height: 16,
});
export const arrow = style({
  width: 16,
  height: 16,
  display: "grid",
  placeItems: "center",
  pointerEvents: "none",
  color: "#FFFFFF",
  selectors: {
    [`${canvas}[data-overlays-ready="false"] &`]: { visibility: "hidden" },
  },
});
export const userPosition = style({
  display: "grid",
  placeItems: "center",
  width: 50,
  height: 50,
  outline: `1px solid ${vars.color.currentLocationBorder}`,
  outlineOffset: -0.5,
  borderRadius: "50%",
  background: vars.color.currentLocationHalo,
  pointerEvents: "none",
  selectors: {
    [`${canvas}[data-overlays-ready="false"] &`]: { visibility: "hidden" },
    "&::after": {
      content: '""',
      display: "block",
      width: 18,
      height: 18,
      outline: `3px solid ${vars.color.surface}`,
      outlineOffset: -1.5,
      borderRadius: "50%",
      background: vars.color.currentLocation,
      boxShadow: `0 1px 5px ${vars.color.currentLocationShadow}`,
    },
  },
});
