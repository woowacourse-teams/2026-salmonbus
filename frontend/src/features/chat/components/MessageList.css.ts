import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const EMPTY_MIN_HEIGHT = "190px";
const EMPTY_IMAGE_SIZE = "92px";
const SKELETON_BAR_HEIGHT = "42px";
const OTHER_BUBBLE_RADIUS = "4px 16px 16px";
const OWN_BUBBLE_RADIUS = "16px 4px 16px 16px";

export const list = style({
  position: "relative",
  display: "flex",
  minHeight: 0,
  flex: 1,
  flexDirection: "column",
  gap: "14px",
  overflowY: "auto",
  overscrollBehavior: "contain",
  padding: "12px 18px",
  scrollbarWidth: "thin",
  selectors: {
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: "-2px" },
  },
});

export const empty = style({
  display: "flex",
  minHeight: EMPTY_MIN_HEIGHT,
  flex: 1,
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  padding: "14px 24px 24px",
  color: vars.color.textMuted,
  textAlign: "center",
  fontSize: "12px",
  lineHeight: 1.5,
});

export const emptyImage = style({
  width: EMPTY_IMAGE_SIZE,
  height: EMPTY_IMAGE_SIZE,
  marginBottom: "3px",
  objectFit: "contain",
});

export const emptyTitle = style({
  marginBottom: "4px",
  color: vars.color.textStrong,
  fontSize: "14px",
  fontWeight: 700,
});

export const messages = style({ display: "flex", flexDirection: "column", gap: "12px", marginTop: "auto" });

export const skeleton = style({ display: "flex", flexDirection: "column", gap: "14px", marginTop: "auto" });

const skeletonBarBase = style({
  height: SKELETON_BAR_HEIGHT,
  borderRadius: OTHER_BUBBLE_RADIUS,
  background: vars.color.compactRowSurface,
});

export const skeletonBar = styleVariants({
  first: [skeletonBarBase, { width: "68%" }],
  second: [skeletonBarBase, { width: "54%", alignSelf: "flex-end", borderRadius: OWN_BUBBLE_RADIUS }],
  third: [skeletonBarBase, { width: "76%" }],
});
