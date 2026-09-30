import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import {
  DESKTOP_MEDIA,
  MOBILE_MOTION_MEDIA,
  SHEET_HEIGHT_VAR,
  VIEWPORT_HEIGHT_VAR,
  VIEWPORT_TOP_VAR,
  WIDE_MOBILE_MEDIA,
} from "../chatLayout";

const VIEWPORT_HEIGHT = `var(${VIEWPORT_HEIGHT_VAR}, 100vh)`;
const VIEWPORT_TOP = `var(${VIEWPORT_TOP_VAR}, 0px)`;
const SHEET_HEIGHT = `var(${SHEET_HEIGHT_VAR}, 52vh)`;
const CARD_INSET = "18px";
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
  top: "auto",
  right: 0,
  bottom: `calc(100% - ${VIEWPORT_TOP} - ${VIEWPORT_HEIGHT})`,
  left: 0,
  height: SHEET_HEIGHT,
  overflow: "hidden",
  background: vars.color.surface,
  color: vars.color.textStrong,
  pointerEvents: "auto",
  outline: "none",
  "@media": {
    [WIDE_MOBILE_MEDIA]: {
      right: CARD_INSET,
      left: CARD_INSET,
    },
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
  border: `1px solid ${vars.color.cardBorder}`,
  borderBottom: 0,
  borderRadius: SHEET_RADIUS,
  boxShadow: SHEET_SHADOW,
});

const openSheet = style([
  sheetBase,
  sheetEdge,
  {
    transform: "translateY(0)",
    "@media": {
      [DESKTOP_MEDIA]: desktopPanel,
    },
  },
]);

export const sheet = styleVariants({
  half: [openSheet],
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

export const content = style({
  position: "relative",
  display: "flex",
  height: "100%",
  flexDirection: "column",
});
