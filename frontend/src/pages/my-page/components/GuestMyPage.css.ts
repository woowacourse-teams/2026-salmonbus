import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const page = style({ fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif", color: "#292A2D" });
export const title = style({ margin: 0, fontSize: 25, fontWeight: 700, lineHeight: "36px" });
export const welcome = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 10,
  padding: "68px 0 16px",
});
export const profile = style({
  width: 64,
  height: 64,
  objectFit: "contain",
});
export const headline = style({ margin: 0, fontSize: 22, fontWeight: 700, lineHeight: "32px", textAlign: "center" });
export const description = style({
  width: "100%",
  margin: 0,
  color: "#78716B",
  fontSize: 14,
  lineHeight: 1.6,
  textAlign: "center",
  overflowWrap: "anywhere",
});
export const login = style({
  height: 52,
  minHeight: 52,
  borderRadius: 13,
  border: `1px solid ${vars.color.liveBus}`,
  background: vars.color.liveBus,
  fontSize: 16,
  fontWeight: 700,
});
