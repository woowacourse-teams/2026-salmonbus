import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { DESKTOP_MEDIA } from "../chatLayout";

const GRABBER_ROW_HEIGHT = "20px";
const SAFE_AREA_TOP = "env(safe-area-inset-top, 0px)";
const GRABBER_WIDTH = "64px";
const GRABBER_HEIGHT = "24px";
const GRABBER_BAR_WIDTH = "36px";
const GRABBER_BAR_HEIGHT = "4px";
const TITLE_ROW_HEIGHT = "48px";
const BUTTON_SIZE = "44px";
const ICON_SIZE = "20px";

export const header = style({
  flexShrink: 0,
  borderBottom: `1px solid ${vars.color.rowDivider}`,
  background: vars.color.surface,
  touchAction: "none",
  userSelect: "none",
});

const grabberRowBase = style({
  display: "flex",
  height: GRABBER_ROW_HEIGHT,
  alignItems: "flex-start",
  justifyContent: "center",
  "@media": {
    [DESKTOP_MEDIA]: { display: "none" },
  },
});

export const grabberRow = styleVariants({
  collapsed: [grabberRowBase],
  half: [grabberRowBase],
  full: [
    grabberRowBase,
    {
      paddingTop: SAFE_AREA_TOP,
      height: `calc(${GRABBER_ROW_HEIGHT} + ${SAFE_AREA_TOP})`,
    },
  ],
});

export const grabber = style({
  position: "relative",
  zIndex: 1,
  display: "flex",
  width: GRABBER_WIDTH,
  height: GRABBER_HEIGHT,
  flexShrink: 0,
  alignItems: "center",
  justifyContent: "center",
  padding: "0 0 4px",
  border: 0,
  borderRadius: vars.radius.badge,
  background: "transparent",
  cursor: "ns-resize",
  WebkitTapHighlightColor: "transparent",
  selectors: {
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: "-2px" },
  },
});

export const grabberBar = style({
  display: "block",
  width: GRABBER_BAR_WIDTH,
  height: GRABBER_BAR_HEIGHT,
  borderRadius: "2px",
  background: vars.color.iconMuted,
});

export const titleRow = style({
  display: "flex",
  height: TITLE_ROW_HEIGHT,
  alignItems: "center",
  gap: "4px",
  padding: "0 6px 4px 18px",
});

export const titleBlock = style({ minWidth: 0, flex: 1 });

export const title = style({
  margin: 0,
  fontSize: "17px",
  fontWeight: 700,
  lineHeight: 1.25,
  letterSpacing: "-0.3px",
});

export const subtitle = style({
  overflow: "hidden",
  margin: "2px 0 0",
  color: vars.color.textMuted,
  fontSize: "11.5px",
  fontWeight: 500,
  textOverflow: "ellipsis",
  whiteSpace: "nowrap",
});

export const collapseButton = style({
  display: "grid",
  flexShrink: 0,
  width: BUTTON_SIZE,
  height: BUTTON_SIZE,
  padding: 0,
  border: 0,
  borderRadius: vars.radius.control,
  placeItems: "center",
  background: "transparent",
  color: vars.color.textSubtle,
  cursor: "pointer",
  selectors: {
    "&:hover": { background: vars.color.compactRowSurface, color: vars.color.ink },
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: "-2px" },
  },
});

export const collapseIcon = style({
  width: ICON_SIZE,
  height: ICON_SIZE,
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.8,
  strokeLinecap: "round",
  strokeLinejoin: "round",
});
