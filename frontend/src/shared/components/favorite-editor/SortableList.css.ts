import { globalStyle, style } from "@vanilla-extract/css";
import gripIcon from "@/shared/assets/icons/grip.svg";

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
globalStyle(`${list}[data-sorting="true"] > li:not([data-dragging="true"])`, {
  transition: "transform 180ms cubic-bezier(0.22, 1, 0.36, 1)",
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});
