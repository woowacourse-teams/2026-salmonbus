import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const backTitle = style({ display: "flex", alignItems: "center", gap: 12, marginBottom: 6 });

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

export const title = style({ margin: 0, fontSize: 25, fontWeight: 700, lineHeight: 1.3, wordBreak: "keep-all" });

export const period = style({ margin: "0 0 24px", paddingLeft: 44, color: "#68645F", fontSize: 12, lineHeight: 1.7 });

export const currentEditionLink = style({
  display: "inline-flex",
  alignItems: "center",
  minHeight: 44,
  margin: "-16px 0 16px 44px",
  color: vars.color.brand,
  fontSize: 13,
  fontWeight: 700,
});

export const notice = style({ margin: "16px 0 24px", fontSize: 14, lineHeight: 1.7 });

export const otherEditions = style({ marginTop: 40, paddingTop: 24, borderTop: `1px solid ${vars.color.rowDivider}` });

export const otherEditionsTitle = style({ margin: "0 0 4px", fontSize: 17, fontWeight: 700, lineHeight: "25px" });

export const editionList = style({ margin: 0, padding: 0, listStyle: "none" });

export const editionLink = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  minHeight: 48,
  color: "inherit",
  fontSize: 14,
  textDecoration: "none",
});
