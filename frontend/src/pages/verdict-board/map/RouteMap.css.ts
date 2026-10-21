import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const region = style({
  position: "relative",
  width: "100%",
  height: "100%",
  clipPath: `inset(0 0 calc(-1 * ${vars.radius.boardSheet}))`,
  isolation: "isolate",
  background: vars.color.surface,
});
export const canvasViewport = style({
  position: "absolute",
  top: "50%",
  left: 0,
  width: "100%",
  transform: "translateY(-50%)",
  background: vars.color.surface,
});
export const canvas = style({ position: "relative", zIndex: 0, width: "100%", height: "100%" });
// SDK가 캔버스 하단에 배치한 출처 표시는 실제로 보이는 지도 하단으로 올린다.
globalStyle(`${canvas} > div:has(img[alt="NAVER"])`, {
  translate: "0 calc(-1 * var(--map-crop-inset, 0px))",
});
export const notice = style({
  position: "absolute",
  inset: 0,
  zIndex: 1,
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 8,
  padding: "52px 24px",
  background: vars.color.surface,
  color: vars.color.textMuted,
  fontSize: 13,
  textAlign: "center",
  pointerEvents: "auto",
});
export const pathNotice = style({
  position: "absolute",
  top: "calc(env(safe-area-inset-top, 0px) + 16px)",
  left: 64,
  right: 16,
  zIndex: 30,
  padding: "8px 12px",
  borderRadius: 8,
  background: vars.color.surface,
  color: vars.color.textMuted,
  fontSize: 12,
  lineHeight: 1.5,
  boxShadow: "0 2px 8px #00000015",
  pointerEvents: "none",
});
export const location = style({
  position: "absolute",
  bottom: 16,
  right: 16,
  zIndex: 30,
  display: "grid",
  placeItems: "center",
  width: 42,
  height: 42,
  border: `1px solid ${vars.color.rowDivider}`,
  borderRadius: 21,
  background: vars.color.surface,
  color: vars.color.ink,
  boxShadow: "0 2px 8px #00000015",
  cursor: "pointer",
  selectors: {
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 2 },
    "&:disabled": { opacity: 0.65, cursor: "wait" },
  },
});
export const locationNotice = style({
  position: "absolute",
  bottom: 66,
  right: 16,
  left: 16,
  zIndex: 30,
  padding: "8px 12px",
  borderRadius: 8,
  background: vars.color.surface,
  color: vars.color.textBody,
  fontSize: 12,
  textAlign: "center",
  boxShadow: "0 2px 8px #00000015",
});
export const stop = style({
  position: "relative",
  display: "grid",
  placeItems: "center",
  width: 24,
  height: 24,
  padding: 0,
  border: "2px solid #C95A50",
  borderRadius: "50%",
  background: vars.color.surface,
  color: "#C95A50",
  boxShadow: "0 1px 4px #0002",
  cursor: "pointer",
  selectors: {
    [`${canvas}[data-overlays-ready="false"] &`]: { visibility: "hidden" },
    '&[data-active="false"]': {
      borderColor: vars.color.textMuted,
      background: vars.color.mutedChipSurface,
      color: vars.color.textMuted,
    },
    '&[aria-pressed="true"]': {
      width: 32,
      height: 32,
      background: "#C95A50",
      color: vars.color.surface,
      borderWidth: 3,
    },
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 2 },
  },
});
export const stopName = style({
  position: "absolute",
  left: "50%",
  top: "calc(100% + 4px)",
  transform: "translateX(-50%)",
  padding: "3px 5px",
  color: vars.color.onInk,
  WebkitTextStroke: "2px #000000",
  paintOrder: "stroke fill",
  whiteSpace: "nowrap",
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  fontSize: 13,
  fontWeight: 800,
  pointerEvents: "none",
  selectors: {
    [`${stop}[data-label-above="true"] &`]: {
      top: "auto",
      bottom: "calc(100% + 4px)",
    },
  },
});

export const stopIcon = style({ width: "65%", height: "65%", display: "block" });
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
  padding: "2px 6px 0",
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
