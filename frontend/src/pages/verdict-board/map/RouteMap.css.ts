import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const region = style({ position: "relative", width: "100%", overflow: "hidden", background: "#e7eceb" });
export const canvas = style({ width: "100%", height: "100%" });
export const notice = style({
  position: "absolute",
  inset: 0,
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 8,
  padding: "52px 24px",
  color: vars.color.textMuted,
  fontSize: 13,
  textAlign: "center",
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
  padding: "2px 5px",
  borderRadius: 3,
  background: "#ffffffe8",
  color: vars.color.textStrong,
  whiteSpace: "nowrap",
  fontSize: 11,
  fontWeight: 600,
  pointerEvents: "none",
});

export const stopIcon = style({ width: "65%", height: "65%", display: "block" });
export const bus = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  width: 40,
  height: 52,
  gap: 2,
  color: vars.color.onInk,
  fontSize: 11,
  fontWeight: 700,
  whiteSpace: "nowrap",
  pointerEvents: "none",
});
export const busCount = style({
  minWidth: 32,
  height: 18,
  padding: "1px 4px",
  lineHeight: "16px",
  borderRadius: 4,
  textAlign: "center",
  background: vars.color.liveBus,
  boxShadow: "0 1px 4px #0002",
});
export const busPin = style({
  position: "relative",
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 30,
  height: 30,
  borderRadius: "50%",
  background: vars.color.liveBus,
  boxShadow: "0 1px 4px #0002",
  selectors: {
    "&::after": {
      content: '""',
      position: "absolute",
      bottom: -3,
      left: 11,
      width: 8,
      height: 8,
      background: vars.color.liveBus,
      transform: "rotate(45deg)",
      zIndex: -1,
    },
  },
});
export const arrow = style({
  width: 16,
  height: 16,
  display: "grid",
  placeItems: "center",
  pointerEvents: "none",
  color: "#FFFFFF",
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
