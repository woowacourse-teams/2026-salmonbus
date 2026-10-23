import { globalStyle, keyframes, style } from "@vanilla-extract/css";
import gripIcon from "@/shared/assets/icons/grip.svg";
import { pill } from "@/shared/styles/selection.css";
import { outlinedCard } from "@/shared/styles/surface.css";
import { vars } from "@/shared/styles/tokens.css";

const list = style({ display: "flex", gap: 12, margin: 0, padding: 0, listStyle: "none" });
export const groups = style([list, { gap: 8, minHeight: 37, alignItems: "center" }]);
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
    padding: "0 12px",
    selectors: {
      '&:has([aria-pressed="true"])': { background: vars.color.liveBus, color: "white" },
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
const revealGroupGrip = keyframes({
  from: { width: 0, marginLeft: 0, opacity: 0 },
  to: { width: 15, marginLeft: 6, opacity: 1 },
});

export const groupGrip = style([
  grip,
  {
    width: 15,
    height: 37,
    marginLeft: 6,
    overflow: "hidden",
    animation: `${revealGroupGrip} ${vars.duration.spring} ${vars.easing.slide} both`,
    "@media": { "(prefers-reduced-motion: reduce)": { animation: "none" } },
    color: "#837B74",
    selectors: {
      "&::before": { width: 15, height: 15 },
      '[aria-pressed="true"] + &': { color: "white" },
    },
  },
]);
globalStyle(`${list}[data-sorting="true"] > li:not([data-dragging="true"])`, {
  transition: "transform 180ms cubic-bezier(0.22, 1, 0.36, 1)",
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});
