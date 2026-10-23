import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { primary } from "@/shared/styles/button.css";

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

export const caption = style({ color: "#68645F", fontSize: 12, lineHeight: 1.7, margin: "8px 0 24px" });

export const actions = style({ display: "flex", gap: 10, marginTop: 20 });

globalStyle(`${login} button:focus-visible, ${login} a:focus-visible`, {
  outline: `3px solid ${vars.color.focusRing}`,
  outlineOffset: 4,
});
