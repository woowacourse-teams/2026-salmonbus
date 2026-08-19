import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const track = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  alignSelf: "stretch",
  width: "16px",
  flexShrink: 0,
});

export const line = style({
  flex: 1,
  width: "2px",
  backgroundColor: vars.color.line,
});

export const hiddenLine = style([
  line,
  {
    backgroundColor: "transparent",
  },
]);

const dotBase = style({
  borderStyle: "solid",
  borderRadius: "50%",
  backgroundColor: vars.color.surface,
  flexShrink: 0,
});

const boardingDot = style([
  dotBase,
  {
    width: "12px",
    height: "12px",
    borderWidth: "2px",
  },
]);

export const dot = styleVariants({
  high: [boardingDot, { borderColor: vars.color.seatHigh }],
  low: [boardingDot, { borderColor: vars.color.seatLow }],
  veryLow: [boardingDot, { borderColor: vars.color.seatVeryLow }],
  passThrough: [
    dotBase,
    {
      width: "8px",
      height: "8px",
      borderWidth: "1.5px",
      borderColor: vars.color.passThroughDot,
    },
  ],
});
