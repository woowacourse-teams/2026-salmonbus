import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const chipBase = style({
  display: "inline-flex",
  alignItems: "center",
  height: "21px",
  padding: "0 8px",
  borderWidth: "1px",
  borderStyle: "solid",
  borderRadius: vars.radius.control,
  fontSize: vars.font.control,
  fontWeight: 700,
  lineHeight: 1,
  whiteSpace: "nowrap",
});

export const chip = styleVariants({
  high: [
    chipBase,
    {
      color: vars.color.seatHigh,
      borderColor: vars.color.seatHigh,
      backgroundColor: vars.color.seatHighSoft,
    },
  ],
  low: [
    chipBase,
    {
      color: vars.color.seatLow,
      borderColor: vars.color.seatLow,
      backgroundColor: vars.color.seatLowSoft,
    },
  ],
  veryLow: [
    chipBase,
    {
      color: vars.color.seatVeryLow,
      borderColor: vars.color.seatVeryLow,
      backgroundColor: vars.color.seatVeryLowSoft,
      fontSize: vars.font.controlTight,
    },
  ],
});
