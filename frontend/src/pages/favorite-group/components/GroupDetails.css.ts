import { style } from "@vanilla-extract/css";

export const heading = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  fontSize: 15,
  fontWeight: 600,
  margin: "0 0 16px",
});
export const list = style({
  display: "flex",
  flexDirection: "column",
  gap: 16,
  padding: 0,
  margin: 0,
  listStyle: "none",
});
export const card = style({
  display: "flex",
  alignItems: "center",
  gap: 8,
  padding: 15,
  border: "1px solid #EAE6E0",
  borderRadius: 14,
  background: "#FFFFFF",
});
export const info = style({ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 6 });
export const cardHeading = style({ display: "flex", alignItems: "center", gap: 8 });
export const stop = style({ minWidth: 0, overflowWrap: "anywhere", fontSize: 15, fontWeight: 600, lineHeight: "22px" });
export const direction = style({ color: "#837B74", fontSize: 12, lineHeight: "17px" });
export const remove = style({
  flexShrink: 0,
  width: 48,
  height: 32,
  padding: 0,
  border: 0,
  borderRadius: 8,
  background: "#FFF0EB",
  color: "#D65B46",
  font: "inherit",
  fontSize: 12,
  fontWeight: 600,
  cursor: "pointer",
});
export const groupActions = style({ display: "flex", justifyContent: "flex-end", paddingTop: 32 });
export const deleteGroup = style({
  padding: 0,
  border: 0,
  background: "transparent",
  color: "#D65B46",
  font: "inherit",
  fontSize: 14,
  fontWeight: 600,
  lineHeight: "20px",
  cursor: "pointer",
});
