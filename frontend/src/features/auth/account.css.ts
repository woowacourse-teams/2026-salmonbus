import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const shell = style({
  display: "flex",
  flexDirection: "column",
  width: "100%",
  maxWidth: 425,
  minHeight: "100dvh",
  margin: "0 auto",
  background: "#FAF9F7",
  color: "#292A2D",
  paddingBottom: "calc(66px + env(safe-area-inset-bottom, 0px))",
});
export const top = style({ height: "env(safe-area-inset-top, 0px)", background: vars.color.brand, flexShrink: 0 });
export const content = style({ padding: "24px 32px", flex: 1 });
export const title = style({ margin: "0 0 28px", fontSize: 25, fontWeight: 700 });
export const brand = style({
  display: "flex",
  alignItems: "center",
  gap: 8,
  color: "white",
  fontSize: 25,
  fontWeight: 800,
  letterSpacing: "-0.5px",
});
export const mascot = style({ width: 32, height: 36, objectFit: "contain" });
export const primary = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 10,
  width: "100%",
  minHeight: 48,
  padding: "12px 18px",
  border: 0,
  borderRadius: 8,
  background: vars.color.brand,
  color: "white",
  font: "inherit",
  fontSize: 14,
  fontWeight: 600,
  textDecoration: "none",
  cursor: "pointer",
  selectors: { "&:disabled": { background: "#E8E4E0", color: "#76716B", cursor: "default" } },
});
export const secondary = style([primary, { background: "#F0ECE7", color: "#59554F" }]);
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
export const toast = style({
  position: "fixed",
  bottom: "calc(82px + env(safe-area-inset-bottom, 0px))",
  left: "50%",
  transform: "translateX(-50%)",
  width: "calc(100% - 48px)",
  maxWidth: 377,
  zIndex: 30,
  padding: "14px 18px",
  borderRadius: 8,
  background: "#292A2D",
  color: "white",
  fontSize: 13,
  textAlign: "center",
  boxShadow: "0 4px 20px #0002",
});
export const dialog = style({
  border: 0,
  borderRadius: 14,
  padding: 24,
  width: "calc(100% - 64px)",
  maxWidth: 360,
  color: "#292A2D",
  background: "#FAF9F7",
  selectors: { "&::backdrop": { background: "#0006" } },
});
export const dialogTitle = style({ fontSize: 18, margin: "0 0 12px" });
export const actions = style({ display: "flex", gap: 10, marginTop: 20 });
export const navigation = style({
  position: "fixed",
  bottom: 0,
  left: "50%",
  transform: "translateX(-50%)",
  display: "flex",
  width: "100%",
  maxWidth: 425,
  zIndex: 20,
  paddingBottom: "env(safe-area-inset-bottom, 0px)",
  borderTop: "1px solid #EEEAE6",
  background: "white",
});
export const tab = style({
  display: "flex",
  flex: 1,
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 4,
  minHeight: 66,
  color: "#858079",
  fontSize: 10,
  textDecoration: "none",
  selectors: { '&[aria-current="page"]': { color: vars.color.brand } },
});
export const login = style({
  display: "flex",
  flexDirection: "column",
  maxWidth: 425,
  margin: "0 auto",
  minHeight: "100dvh",
  padding: "calc(20px + env(safe-area-inset-top, 0px)) 32px calc(28px + env(safe-area-inset-bottom, 0px))",
  background: vars.color.brand,
  color: "white",
});
globalStyle(`html:has(${login})`, { backgroundColor: vars.color.brand });

export const greeting = style({
  fontSize: 46,
  fontWeight: 400,
  lineHeight: 1.16,
  letterSpacing: "-2px",
  margin: "38px 0 22px",
  whiteSpace: "pre-line",
});
export const greetingGap = style({ display: "block", marginTop: 24 });
export const loginCaption = style({ fontSize: 14, lineHeight: 1.6, margin: 0 });
export const loginActions = style({ display: "grid", gap: 12, marginTop: "auto", paddingTop: 40 });
export const kakao = style([primary, { background: "#FEE500", color: "#191919" }]);
export const apple = style([primary, { background: "#242424", color: "white" }]);
export const back = style([
  primary,
  { background: "transparent", border: "1px solid #FFFFFF80", color: "white", marginTop: 8 },
]);
export const loginNotice = style({ fontSize: 12, lineHeight: 1.6, color: "white", textAlign: "center" });
export const backTitle = style({ display: "flex", alignItems: "center", gap: 12, marginBottom: 28 });
export const backIcon = style({
  display: "grid",
  placeItems: "center",
  width: 40,
  height: 40,
  marginLeft: -10,
  border: 0,
  borderRadius: "50%",
  background: "transparent",
  color: vars.color.brand,
  textDecoration: "none",
  fontSize: 28,
});

globalStyle(
  `${shell} button:focus-visible, ${shell} a:focus-visible, ${login} button:focus-visible, ${login} a:focus-visible, ${dialog} button:focus-visible`,
  { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 4 },
);
