import { style } from "@vanilla-extract/css";

export const section = style({
  display: "flex",
  flexDirection: "column",
  marginTop: 32,
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  color: "#292A2D",
});

export const heading = style({ margin: "0 0 4px", fontSize: 17, fontWeight: 700, lineHeight: "25px" });

export const row = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  minHeight: 48,
  fontSize: 14,
  cursor: "pointer",
});

export const description = style({
  margin: 0,
  color: "#68645F",
  fontSize: 12,
  lineHeight: 1.7,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
});

export const policyLink = style([row, { color: "inherit", textDecoration: "none" }]);
