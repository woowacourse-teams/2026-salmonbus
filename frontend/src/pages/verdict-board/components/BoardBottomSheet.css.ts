import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const sheet = style({
  position: "relative",
  zIndex: 2,
  display: "flex",
  flexShrink: 0,
  flexDirection: "column",
  boxSizing: "border-box",
  overflow: "hidden",
  borderRadius: `${vars.radius.boardSheet} ${vars.radius.boardSheet} 0 0`,
  paddingBottom: "env(safe-area-inset-bottom, 0px)",
  backgroundColor: vars.color.surface,
  boxShadow: "0 -5px 24px #3146320B",
  transition: `height ${vars.duration.spring} ${vars.easing.slide}`,
  selectors: {
    '&[data-dragging="true"]': { transition: "none" },
  },
  "@media": {
    "(prefers-reduced-motion: reduce)": { transition: "none" },
  },
});

export const header = style({
  display: "flex",
  flexShrink: 0,
  flexDirection: "column",
  gap: "10px",
  padding: "10px 18px 15px",
  touchAction: "none",
  userSelect: "none",
});

export const handle = style({
  position: "relative",
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  flexShrink: 0,
  minHeight: "4px",
  width: "100%",
  marginBottom: "5px",
  cursor: "ns-resize",
  borderRadius: vars.radius.control,
  outline: "none",
  selectors: {
    "&::before": {
      content: '""',
      position: "absolute",
      inset: "-10px 0 -15px",
    },
    "&:focus-visible": {
      outline: `2px solid ${vars.color.focusRing}`,
      outlineOffset: "-2px",
    },
  },
});

export const handleBar = style({
  width: "32px",
  height: "4px",
  borderRadius: "3px",
  backgroundColor: "#DCE3D9",
});

export const body = style({
  display: "flex",
  flex: 1,
  minHeight: 0,
  flexDirection: "column",
  padding: "0 18px",
  overflow: "hidden",
  selectors: {
    [`${sheet}[data-snap="minimum"][data-dragging="false"] &`]: { visibility: "hidden" },
  },
});

const safeAreaProbe = style({
  position: "absolute",
  width: 0,
  pointerEvents: "none",
  visibility: "hidden",
});
export const safeTop = style([safeAreaProbe, { height: "env(safe-area-inset-top, 0px)" }]);
export const safeBottom = style([safeAreaProbe, { height: "env(safe-area-inset-bottom, 0px)" }]);
