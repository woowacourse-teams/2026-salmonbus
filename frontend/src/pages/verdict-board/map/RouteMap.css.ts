import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const region = style({
  position: "relative",
  width: "100%",
  height: "100%",
  clipPath: `inset(0 0 calc(-1 * ${vars.radius.boardSheet}))`,
  isolation: "isolate",
  background: vars.color.surface,
  selectors: { 'html[data-native-map="true"] &': { background: "transparent" } },
});
export const canvasViewport = style({
  position: "absolute",
  top: "50%",
  left: 0,
  width: "100%",
  transform: "translateY(-50%)",
  background: vars.color.surface,
  selectors: { 'html[data-native-map="true"] &': { background: "transparent" } },
});
export const canvas = style({ position: "relative", zIndex: 0, width: "100%", height: "100%" });
// SDK가 캔버스 하단에 배치한 출처 표시는 실제로 보이는 지도 하단으로 올린다.
globalStyle(`${canvas} > div:has(img[alt="NAVER"])`, {
  translate: "0 calc(-1 * var(--map-crop-inset, 0px))",
});
export const notice = style({
  position: "absolute",
  inset: 0,
  zIndex: 1,
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 8,
  padding: "52px 24px",
  background: vars.color.surface,
  color: vars.color.textMuted,
  fontSize: 13,
  textAlign: "center",
  pointerEvents: "auto",
});
export const pathNotice = style({
  position: "absolute",
  top: "calc(env(safe-area-inset-top, 0px) + 16px)",
  left: 64,
  right: 16,
  zIndex: 30,
  padding: "8px 12px",
  borderRadius: 8,
  background: vars.color.surface,
  color: vars.color.textMuted,
  fontSize: 12,
  lineHeight: 1.5,
  boxShadow: "0 2px 8px #00000015",
  pointerEvents: "none",
});
export const location = style({
  position: "absolute",
  bottom: 16,
  right: 16,
  zIndex: 30,
  display: "grid",
  placeItems: "center",
  width: 42,
  height: 42,
  border: `1px solid ${vars.color.rowDivider}`,
  borderRadius: 21,
  background: vars.color.surface,
  color: vars.color.ink,
  boxShadow: "0 2px 8px #00000015",
  cursor: "pointer",
  selectors: {
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 2 },
    "&:disabled": { opacity: 0.65, cursor: "wait" },
  },
});
export const locationNotice = style({
  position: "absolute",
  bottom: 66,
  right: 16,
  left: 16,
  zIndex: 30,
  padding: "8px 12px",
  borderRadius: 8,
  background: vars.color.surface,
  color: vars.color.textBody,
  fontSize: 12,
  textAlign: "center",
  boxShadow: "0 2px 8px #00000015",
});
export const locationPromptText = style({ margin: 0 });
export const locationPromptActions = style({
  display: "flex",
  justifyContent: "center",
  gap: 8,
  marginTop: 8,
});
const locationPromptButton = style({
  minHeight: 36,
  padding: "0 14px",
  border: `1px solid ${vars.color.rowDivider}`,
  borderRadius: 8,
  background: vars.color.surface,
  color: vars.color.textBody,
  fontSize: 12,
  fontWeight: 600,
  cursor: "pointer",
  selectors: {
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 2 },
  },
});
export const locationSettingsButton = style([
  locationPromptButton,
  { borderColor: vars.color.brand, background: vars.color.brand, color: vars.color.onInk },
]);
export const locationDismissButton = locationPromptButton;
