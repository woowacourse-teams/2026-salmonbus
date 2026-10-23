import { style } from "@vanilla-extract/css";

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
export const groupActions = style({
  width: "100%",
  display: "flex",
  justifyContent: "flex-end",
  marginTop: 16,
  paddingTop: 16,
  paddingRight: 8,
});
export const deleteGroup = style({
  margin: 0,
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

export const dialog = style({
  selectors: {
    "&&": {
      width: "calc(100% - 64px)",
      maxWidth: 356,
      borderRadius: 14,
      padding: "20px 18px",
      background: "#FFFFFF",
      fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
    },
    "&&::backdrop": { background: "#19151266" },
  },
});
export const favoriteDialog = style({ boxShadow: "0 12px 36px #19151226" });
export const dialogTitle = style({
  selectors: {
    "&&": {
      margin: "0 0 18px",
      fontSize: 20,
      fontWeight: 700,
      lineHeight: 1.4,
      letterSpacing: "-0.6px",
      overflowWrap: "anywhere",
    },
  },
});
export const groupTitle = style([dialogTitle, { selectors: { "&&": { fontSize: 21 } } }]);
export const target = style({
  display: "flex",
  flexDirection: "column",
  gap: 8,
  padding: 14,
  borderRadius: 10,
  background: "#FAF9F7",
});
export const targetHeading = style({ display: "flex", alignItems: "center", gap: 8 });
export const targetStop = style({
  minWidth: 0,
  overflowWrap: "anywhere",
  fontSize: 14,
  fontWeight: 600,
  letterSpacing: "-0.4px",
  lineHeight: "20px",
});
export const description = style({
  margin: 0,
  color: "#837B74",
  fontSize: 14,
  lineHeight: 1.65,
  overflowWrap: "anywhere",
});
export const dialogActions = style({ display: "flex", gap: 10, marginTop: 18 });
export const groupDialogActions = style([dialogActions, { marginTop: 26 }]);
export const confirm = style({
  flex: 1,
  minWidth: 0,
  height: 48,
  padding: 0,
  border: 0,
  borderRadius: 14,
  background: "#E8533F",
  color: "#FFFFFF",
  font: "inherit",
  fontSize: 14,
  fontWeight: 700,
  cursor: "pointer",
  selectors: { "&:disabled": { opacity: 0.45, cursor: "default" } },
});
export const cancel = style([confirm, { background: "#F1EEEA", color: "#76706A" }]);
