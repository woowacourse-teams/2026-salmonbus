import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const rowBase = style({
  display: "grid",
  gridTemplateColumns: "1fr auto 1fr",
  alignItems: "center",
  height: "71px",
  padding: "0 12px",
});

export const boardingRow = styleVariants({
  high: [rowBase, { backgroundColor: vars.color.seatHighTint }],
  low: [rowBase, { backgroundColor: vars.color.seatLowTint }],
  veryLow: [rowBase, { backgroundColor: vars.color.seatVeryLowTint }],
});

export const passThroughRow = style([
  rowBase,
  {
    height: "49px",
    backgroundColor: vars.color.surface,
  },
]);

export const nameCell = style({
  display: "flex",
  alignItems: "baseline",
  gap: "4px",
  minWidth: 0,
});

export const passThroughCaption = style({
  color: vars.color.textMuted,
  fontSize: vars.font.caption,
  fontWeight: 500,
  flexShrink: 0,
});

export const verdictCell = style({
  display: "flex",
  justifyContent: "center",
});
