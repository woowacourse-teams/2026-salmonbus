import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { text } from "@/shared/styles/button.css";

export const section = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  maxWidth: 356,
  margin: "0 auto 24px",
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  color: "#292A2D",
});
export const edit = style([
  text,
  { display: "grid", placeItems: "center", flexShrink: 0, width: 36, height: 32, padding: "6px 8px" },
]);
export const heading = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  margin: 0,
  fontSize: 17,
  fontWeight: 700,
  lineHeight: "25px",
  whiteSpace: "pre-wrap",
});
export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
export const group = style({
  display: "flex",
  alignItems: "center",
  gap: 12,
  padding: 17,
  border: "1px solid #EAE6E0",
  borderRadius: 14,
  background: vars.color.surface,
  color: "inherit",
  textDecoration: "none",
});
export const info = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "flex-start",
  gap: 5,
  flex: 1,
  minWidth: 0,
});
export const name = style({ fontSize: 16, fontWeight: 600, lineHeight: "23px", overflowWrap: "anywhere" });
export const count = style({
  padding: "4px 9px",
  borderRadius: 7,
  background: "#FFF0EB",
  color: "#C95642",
  fontSize: 12,
  fontWeight: 600,
  lineHeight: "17px",
});
export const add = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 7,
  height: 50,
  border: "1px solid #DFD8D0",
  borderRadius: 14,
  color: vars.color.liveBus,
  fontSize: 14,
  fontWeight: 600,
  textDecoration: "none",
});
