import { style } from "@vanilla-extract/css";
import { PHONE_MEDIA } from "@/shared/styles/media";
import { vars } from "@/shared/styles/tokens.css";

const SAFE_AREA_TOP = "env(safe-area-inset-top, 0px)";
const SAFE_AREA_BOTTOM = "env(safe-area-inset-bottom, 0px)";

export const page = style({
  position: "fixed",
  right: 0,
  left: 0,
  maxWidth: "430px",
  "@media": { [PHONE_MEDIA]: { maxWidth: "none" } },
  margin: "0 auto",
  overflow: "hidden",
  backgroundColor: vars.color.background,
  selectors: { 'html[data-native-map="true"] &': { backgroundColor: "transparent" } },
});
export const content = style({
  position: "relative",
  display: "flex",
  flexDirection: "column",
  width: "100%",
  height: "100%",
});
export const mapSlot = style({ position: "relative", flex: 1, minHeight: 0 });
export const back = style({
  position: "absolute",
  top: "calc(env(safe-area-inset-top, 0px) + 20px)",
  left: "20px",
  zIndex: 1,
  display: "grid",
  placeItems: "center",
  width: "32px",
  height: "32px",
  borderRadius: "50%",
  backgroundColor: vars.color.liveBus,
  color: "#FFFFFF",
  selectors: { "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: "3px" } },
});
export const fullControls = style({
  position: "relative",
  height: "36px",
});
export const fullBack = style([back, { top: 0, left: 0 }]);
export const outOfService = style({
  alignSelf: "center",
  width: "100%",
  textAlign: "center",
  color: vars.color.textSubtle,
  fontSize: vars.font.boardNotice,
});
export const alternateDirection = style({
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: vars.radius.control,
  padding: "10px 14px",
  background: vars.color.surface,
  color: vars.color.textBody,
  cursor: "pointer",
});
export const noticePage = style({
  position: "relative",
  display: "flex",
  width: "min(100%, 430px)",
  "@media": { [PHONE_MEDIA]: { width: "100%" } },
  height: ["100vh", "100dvh"],
  margin: "0 auto",
  padding: `calc(64px + ${SAFE_AREA_TOP}) 18px calc(32px + ${SAFE_AREA_BOTTOM})`,
  backgroundColor: vars.color.background,
});
