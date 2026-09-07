import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

const chipBase = style({
  display: "inline-flex",
  alignItems: "center",
  justifyContent: "center",
  flexShrink: 0,
  height: "19px",
  padding: "0 4px",
  borderRadius: vars.radius.badge,
  fontSize: vars.font.verdict,
  fontWeight: 600,
  lineHeight: 1,
  whiteSpace: "nowrap",
});

const chipMinWidth: Record<keyof typeof vars.color.seat, string> = {
  high: "73px",
  low: "73px",
  veryLow: "96px",
};

const seatChip = styleVariants(vars.color.seat, (tone, level) => [
  chipBase,
  {
    minWidth: chipMinWidth[level],
    backgroundColor: tone.surface,
    color: tone.text,
  },
]);

const noForecastChip = style([
  chipBase,
  {
    minWidth: "118px",
    backgroundColor: vars.color.mutedChipSurface,
    color: vars.color.textSubtle,
  },
]);

export const chip = { ...seatChip, noForecast: noForecastChip };
