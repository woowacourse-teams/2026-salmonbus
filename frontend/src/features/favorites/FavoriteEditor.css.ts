import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { primary, secondary } from "@/shared/styles/button.css";
import { section as homeSection, route, heading } from "./HomeFavorites.css";
import { dialogTitle } from "@/shared/components/ConfirmDialog.css";
import gripIcon from "./grip.svg";

export const editor = style([
  homeSection,
  {
    display: "flex",
    flexDirection: "column",
    gap: 12,
    fontFamily: "'Noto Sans KR', sans-serif",
  },
]);
export const srOnly = style({
  position: "absolute",
  width: 1,
  height: 1,
  padding: 0,
  margin: -1,
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap",
  border: 0,
});
const list = style({ display: "flex", gap: 12, margin: 0, padding: 0, listStyle: "none" });
export const groups = style([list, { gap: 8 }]);
export const cards = style([list, { flexDirection: "column" }]);
const item = style({
  position: "relative",
  minWidth: 0,
  selectors: {
    '&[data-dragging="true"]': { zIndex: 1, boxShadow: "0 8px 24px #292A2D24", cursor: "grabbing" },
  },
});
export const group = style([
  item,
  {
    display: "flex",
    alignItems: "center",
    gap: 6,
    padding: "0 12px",
    borderRadius: 20,
    background: "#EFEEEA",
    color: "#837B74",
    selectors: {
      '&:has([aria-pressed="true"])': { background: "#E8533F", color: "white" },
    },
  },
]);
export const groupName = style({
  minWidth: 0,
  padding: "9px 0",
  border: 0,
  background: "transparent",
  color: "#706B66",
  selectors: { '&[aria-pressed="true"]': { color: "white" } },
  font: "inherit",
  fontSize: 13,
  fontWeight: 600,
  lineHeight: "19px",
  whiteSpace: "nowrap",
  overflow: "hidden",
  textOverflow: "ellipsis",
  cursor: "pointer",
});
export const card = style([
  item,
  {
    display: "flex",
    alignItems: "center",
    gap: 12,
    minHeight: 100,
    padding: 16,
    borderRadius: 14,
    background: "white",
  },
]);
export const info = style({ display: "flex", flexDirection: "column", gap: 6, flex: 1, minWidth: 0 });
export const cardHeading = style([heading, { height: "auto", flexWrap: "wrap" }]);
export { route };
export const stop = style({
  flex: "1 1 100px",
  minWidth: 0,
  fontSize: 15,
  fontWeight: 600,
  lineHeight: "22px",
  color: "#292A2D",
  overflowWrap: "anywhere",
});
export const direction = style({ color: "#837B74", fontSize: 12, lineHeight: "18px" });
export const trash = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 36,
  height: 36,
  padding: 0,
  border: 0,
  borderRadius: 6,
  background: "transparent",
  cursor: "pointer",
});
export const grip = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 22,
  height: 44,
  padding: 0,
  border: 0,
  background: "transparent",
  color: "#A5998B",
  touchAction: "none",
  userSelect: "none",
  cursor: "grab",
  selectors: {
    "&::before": {
      content: "''",
      width: 22,
      height: 22,
      background: "currentColor",
      mask: `url(${gripIcon}) center / contain no-repeat`,
    },
    "&:disabled": { cursor: "default" },
    "&:active": { cursor: "grabbing" },
  },
});
export const groupGrip = style([
  grip,
  { width: 15, height: 37, color: "inherit", selectors: { "&::before": { width: 15, height: 15 } } },
]);
export const done = style([
  primary,
  { minHeight: 46, borderRadius: 14, fontWeight: 700, padding: "12px 18px", background: vars.color.liveBus },
]);
export const deleteDialog = style({
  selectors: {
    "&&": {
      width: "calc(100% - 56px)",
      maxWidth: 369,
      borderRadius: 24,
      padding: 24,
      background: "white",
      fontFamily: "'Noto Sans KR', sans-serif",
    },
  },
});
globalStyle(`${deleteDialog} ${dialogTitle}`, {
  margin: "0 0 16px",
  fontSize: 21,
  fontWeight: 700,
  lineHeight: 1.4,
  letterSpacing: "-0.5px",
  textAlign: "center",
});
export const deleteDescription = style({
  margin: 0,
  color: "#76706A",
  fontSize: 13,
  lineHeight: 1.65,
  textAlign: "center",
  overflowWrap: "anywhere",
});
export const deleteActions = style({ display: "flex", gap: 10, marginTop: 20 });
export const cancel = style([
  secondary,
  { minHeight: 50, borderRadius: 12, background: "#F0EFEC", color: "#706B66", fontWeight: 700 },
]);
export const confirm = style([
  primary,
  { minHeight: 50, borderRadius: 12, fontWeight: 700, background: vars.color.liveBus },
]);
globalStyle(`${editor} button:focus-visible`, { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 3 });
globalStyle(`${editor} [data-sorting="true"] > li:not([data-dragging="true"])`, {
  transition: "transform 180ms cubic-bezier(0.22, 1, 0.36, 1)",
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});
