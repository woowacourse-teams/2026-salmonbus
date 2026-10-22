import { style } from "@vanilla-extract/css";

export const title = style({ margin: "0 0 28px", fontSize: 25, fontWeight: 700 });

export const row = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  width: "100%",
  minHeight: 48,
  padding: "8px 0",
  border: 0,
  background: "transparent",
  color: "inherit",
  font: "inherit",
  fontSize: 14,
  textAlign: "left",
  textDecoration: "none",
  cursor: "pointer",
});

export const nickname = style([row, { minHeight: 32, fontSize: 22, fontWeight: 700 }]);

export const caption = style({ color: "#68645F", fontSize: 12, lineHeight: 1.7, margin: "8px 0 24px" });

export const error = style({ color: "#BA3529", fontSize: 13, lineHeight: 1.6 });

export const actions = style({ display: "flex", gap: 10, marginTop: 20 });
