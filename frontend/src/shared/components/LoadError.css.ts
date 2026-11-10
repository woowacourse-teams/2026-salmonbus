import { style } from "@vanilla-extract/css";

export const container = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 18,
  minHeight: 260,
  marginTop: 41,
  color: "#837B74",
  textAlign: "center",
});
export const title = style({ margin: 0, fontSize: 16, fontWeight: 500, lineHeight: 1.5 });
export const description = style({ margin: 0, fontSize: 13, lineHeight: 1.6 });
