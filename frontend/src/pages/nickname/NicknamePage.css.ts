import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const title = style({ margin: "0 0 28px", fontSize: 25, fontWeight: 700 });

export const caption = style({ color: "#68645F", fontSize: 12, lineHeight: 1.7, margin: "8px 0 24px" });

export const error = style({ color: "#BA3529", fontSize: 13, lineHeight: 1.6 });

export const field = style({
  display: "block",
  width: "100%",
  minHeight: 48,
  marginTop: 12,
  padding: "12px 14px",
  border: "1px solid #EEEAE6",
  borderRadius: 14,
  background: "white",
  font: "inherit",
  fontSize: 16,
});

export const label = style({ fontSize: 13 });

export const backTitle = style({ display: "flex", alignItems: "center", gap: 12, marginBottom: 28 });

export const backIcon = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 32,
  height: 32,
  border: 0,
  borderRadius: "50%",
  backgroundColor: vars.color.liveBus,
  color: vars.color.onBrand,
  textDecoration: "none",
});
