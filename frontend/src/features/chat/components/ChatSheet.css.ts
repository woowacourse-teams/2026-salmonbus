import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import {
  DESKTOP_MEDIA,
  HALF_OFFSET_VAR,
  MOBILE_MOTION_MEDIA,
  VIEWPORT_HEIGHT_VAR,
  VIEWPORT_TOP_VAR,
} from "../chatLayout";

const VIEWPORT_HEIGHT = `var(${VIEWPORT_HEIGHT_VAR}, 100vh)`;
const VIEWPORT_TOP = `var(${VIEWPORT_TOP_VAR}, 0px)`;
const HALF_OFFSET = `var(${HALF_OFFSET_VAR}, 48vh)`;
const HALF_BORDER_WIDTH = "1px";
const SHEET_RADIUS = "16px 16px 0 0";
const SHEET_SHADOW = "0 -4px 24px rgba(17, 17, 17, 0.10)";
const SLIDE = `transform ${vars.duration.spring} ${vars.easing.slide}`;

const desktopPanel = {
  transform: "none",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: vars.radius.card,
  boxShadow: "none",
} as const;

const sheetBase = style({
  position: "absolute",
  top: VIEWPORT_TOP,
  right: 0,
  left: 0,
  height: VIEWPORT_HEIGHT,
  overflow: "hidden",
  background: vars.color.surface,
  color: vars.color.textStrong,
  pointerEvents: "auto",
  outline: "none",
  "@media": {
    [MOBILE_MOTION_MEDIA]: {
      transition: `${SLIDE}, visibility 0s`,
    },
    [DESKTOP_MEDIA]: {
      top: 0,
      bottom: 0,
      height: "auto",
    },
  },
});

const sheetEdge = style({
  border: `${HALF_BORDER_WIDTH} solid ${vars.color.cardBorder}`,
  borderBottom: 0,
  borderRadius: SHEET_RADIUS,
  boxShadow: SHEET_SHADOW,
});

export const sheet = styleVariants({
  half: [
    sheetBase,
    sheetEdge,
    {
      transform: `translateY(${HALF_OFFSET})`,
      "@media": {
        [DESKTOP_MEDIA]: desktopPanel,
      },
    },
  ],
  full: [
    sheetBase,
    {
      transform: "translateY(0)",
      border: 0,
      borderRadius: 0,
      boxShadow: "none",
      "@media": {
        [DESKTOP_MEDIA]: desktopPanel,
      },
    },
  ],
  collapsed: [
    sheetBase,
    sheetEdge,
    {
      visibility: "hidden",
      transform: "translateY(100%)",
      "@media": {
        [MOBILE_MOTION_MEDIA]: {
          transition: `${SLIDE}, visibility 0s linear ${vars.duration.spring}`,
        },
        [DESKTOP_MEDIA]: desktopPanel,
      },
    },
  ],
});

const contentBase = style({
  position: "relative",
  display: "flex",
  height: `calc(${VIEWPORT_HEIGHT} - ${HALF_OFFSET} - ${HALF_BORDER_WIDTH})`,
  flexDirection: "column",
  "@media": {
    [DESKTOP_MEDIA]: { height: "100%" },
  },
});

export const content = styleVariants({
  collapsed: [contentBase],
  half: [contentBase],
  full: [contentBase, { height: "100%" }],
});
