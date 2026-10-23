import { globalStyle, style } from "@vanilla-extract/css";
import gripIcon from "@/shared/assets/icons/grip.svg";
import { pill } from "@/shared/styles/selection.css";
import { outlinedCard } from "@/shared/styles/surface.css";
import { vars } from "@/shared/styles/tokens.css";

const list = style({ display: "flex", gap: 12, margin: 0, padding: 0, listStyle: "none" });
export const groups = style([list, { gap: 8, minHeight: 32, alignItems: "center" }]);
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
  pill,
  {
    display: "flex",
    alignItems: "center",
    padding: 0,
    selectors: {
      '&:has([aria-pressed="true"])': { background: vars.color.brand, color: "white" },
    },
  },
]);
export const card = style([
  item,
  outlinedCard,
  {
    display: "flex",
    alignItems: "center",
    gap: 12,
    minHeight: 100,
    padding: 16,
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
  {
    position: "absolute",
    right: 0,
    top: 0,
    width: 14,
    height: "100%",
    color: "inherit",
    selectors: { "&::before": { width: 12, height: 12 } },
  },
]);
globalStyle(`${list}[data-sorting="true"] > li:not([data-dragging="true"])`, {
  transition: "transform 180ms cubic-bezier(0.22, 1, 0.36, 1)",
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});
