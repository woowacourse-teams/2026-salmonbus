import { style, styleVariants } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { card as cardBase } from "./favorites.css";

export const section = style({ width: "100%", maxWidth: 356, margin: "0 auto" });
export const card = style([
  cardBase,
  {
    padding: "16px 18px",
    border: 0,
    outline: "1px solid #EAE6E0",
    outlineOffset: "-0.5px",
    borderRadius: 14,
    fontFamily: "'Noto Sans KR', sans-serif",
  },
]);
export const link = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  color: "inherit",
  textDecoration: "none",
});
export const heading = style({ display: "flex", alignItems: "center", gap: 8, minWidth: 0, height: 23 });
export const route = style({
  flexShrink: 0,
  padding: "3px 7px",
  borderRadius: 6,
  background: "#FFF0EB",
  color: vars.color.liveBus,
  fontSize: 11,
  fontWeight: 700,
  lineHeight: "16px",
});
export const stop = style({
  flex: 1,
  minWidth: 0,
  overflow: "hidden",
  color: "#292A2D",
  fontSize: 16,
  fontWeight: 700,
  lineHeight: "23px",
  letterSpacing: "-0.5px",
  whiteSpace: "nowrap",
  textOverflow: "ellipsis",
});
export const direction = style({
  flexShrink: 0,
  color: "#837B74",
  fontSize: 11,
  lineHeight: "16px",
  whiteSpace: "nowrap",
});
export const forecast = style({
  overflow: "hidden",
  padding: "0 10px",
  borderRadius: 10,
  background: vars.color.forecastCardSurface,
  boxShadow: `inset 0 0 0 1px ${vars.color.forecastCardBorder}`,
});
export const rows = style({ padding: 0, margin: 0, listStyle: "none" });
export const arrival = style({
  display: "flex",
  alignItems: "center",
  gap: 10,
  height: 44,
  selectors: { "&:not(:first-child)": { boxShadow: `inset 0 1px ${vars.color.forecastCardBorder}` } },
});
export const timing = style({
  display: "flex",
  flex: "1 1 0",
  flexWrap: "wrap",
  alignItems: "center",
  columnGap: 6,
  minWidth: 60,
  paddingLeft: 6,
});
export const eta = style({ color: vars.color.textStrong, fontSize: 12, fontWeight: 600, lineHeight: "17px" });
export const unavailableEta = style({
  color: vars.color.textSubtle,
  fontSize: 10,
  fontWeight: 500,
  lineHeight: "14px",
  maxWidth: "100%",
  wordBreak: "keep-all",
});
export const distance = style({
  color: vars.color.textSubtle,
  fontSize: 10,
  fontWeight: 500,
  lineHeight: "14px",
  whiteSpace: "nowrap",
});
export const seatBadge = styleVariants(
  { ...vars.color.seat, low: { ...vars.color.seat.low, surface: "#F6EEDB", text: "#8C6A25" } },
  (tone) => ({
    selectors: {
      "&&": {
        width: 148,
        height: 22,
        padding: "0 6px",
        fontSize: 12,
        lineHeight: "22px",
        backgroundColor: tone.surface,
        color: tone.text,
      },
    },
  }),
);
export const notice = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  minHeight: 44,
  margin: 0,
  color: vars.color.textSubtle,
  fontSize: 12,
  textAlign: "center",
});
