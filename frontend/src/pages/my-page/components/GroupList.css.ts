import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { card } from "@/shared/styles/surface.css";

export const section = style({ margin: "0 0 24px" });
export const heading = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  margin: "24px 0 12px",
  fontSize: 15,
  fontWeight: 600,
});
export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 12,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
export const count = style({ display: "block", marginTop: 7, fontSize: 12, color: vars.color.brand });
export const add = style([
  card,
  {
    width: "100%",
    marginTop: 12,
    padding: 12,
    textAlign: "center",
    color: vars.color.brand,
    fontSize: 13,
    background: "transparent",
  },
]);
