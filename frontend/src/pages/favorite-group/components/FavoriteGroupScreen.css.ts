import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const screen = style({
  maxWidth: 356,
  margin: "0 auto",
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  color: "#292A2D",
});
export const title = style({ display: "flex", alignItems: "center", gap: 10, margin: "0 0 16px", paddingRight: 12 });
export const titleLabel = style({ display: "flex", alignItems: "center", gap: 8, flex: 1, minWidth: 0 });
export const representative = style({
  flexShrink: 0,
  padding: "4px 8px",
  borderRadius: 12,
  background: "#FFEAE4",
  color: "#B94130",
  fontSize: 11,
  fontWeight: 700,
  lineHeight: 1.3,
});
export const name = style({
  minWidth: 0,
  overflowWrap: "anywhere",
  margin: 0,
  fontSize: 24,
  lineHeight: "35px",
  fontWeight: 700,
});
export const rename = style({
  flexShrink: 0,
  padding: 0,
  border: 0,
  background: "transparent",
  color: vars.color.liveBus,
  font: "inherit",
  fontSize: 12,
  fontWeight: 600,
  lineHeight: "17px",
  cursor: "pointer",
});
export const back = style({
  padding: 0,
  border: 0,
  cursor: "pointer",
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 32,
  height: 32,
  borderRadius: "50%",
  background: vars.color.liveBus,
  color: "white",
  textDecoration: "none",
});
