import { globalStyle, style } from "@vanilla-extract/css";
import { BOARD_SHEET_EXPANDED_PERCENT } from "@/shared/bottom-sheet/boardSheetPolicy";
import { PHONE_MEDIA } from "@/shared/styles/media";
import { vars } from "@/shared/styles/tokens.css";
import { secondary } from "@/shared/styles/button.css";

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
const mapOverlay = style({
  position: "fixed",
  inset: "0 0 auto",
  maxWidth: 425,
  margin: "0 auto",
  zIndex: 5,
  pointerEvents: "none",
  "@media": { [PHONE_MEDIA]: { maxWidth: "none" } },
});
export const sharedPage = style([page, mapOverlay, { backgroundColor: "transparent" }]);
globalStyle(`${sharedPage} [data-native-map-hit], ${sharedPage} a:focus`, { pointerEvents: "auto" });
export const content = style({
  position: "relative",
  display: "flex",
  flexDirection: "column",
  width: "100%",
  height: "100%",
});
export const mapSlot = style({ position: "relative", flex: 1, minHeight: 0 });
export const skipLink = style([
  secondary,
  {
    position: "absolute",
    top: `calc(${SAFE_AREA_TOP} + 8px)`,
    left: 18,
    right: 18,
    zIndex: 3,
    width: "auto",
    selectors: { "&:not(:focus)": { clipPath: "inset(50%)" } },
  },
]);
const mapControlsPosition = style({
  position: "absolute",
  top: "calc(env(safe-area-inset-top, 0px) + 20px)",
  left: "20px",
  zIndex: 1,
});
export const back = style([
  mapControlsPosition,
  {
    display: "grid",
    placeItems: "center",
    width: "32px",
    height: "32px",
    borderRadius: "50%",
    backgroundColor: vars.color.liveBus,
    color: "#FFFFFF",
    selectors: { "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: "3px" } },
  },
]);
export const fullControls = style({
  display: "flex",
  alignItems: "center",
  gap: 8,
  height: "36px",
});
export const mapControls = style([fullControls, mapControlsPosition, { height: "32px" }]);
export const fullBack = style([back, { position: "static", flexShrink: 0 }]);
export const directionHeader = style({
  display: "grid",
  gap: "4px",
});
export const routeNumber = style({
  display: "grid",
  placeItems: "center",
  minWidth: 54,
  height: 32,
  margin: 0,
  padding: "0 10px",
  borderRadius: vars.radius.control,
  backgroundColor: vars.color.liveBus,
  color: vars.color.onInk,
  fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
  fontSize: 18,
  fontWeight: 800,
  lineHeight: 1,
  whiteSpace: "nowrap",
});
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

export const boardNotice = style({
  margin: 0,
  color: vars.color.textSubtle,
  fontSize: vars.font.boardNotice,
  textAlign: "center",
});

export const sharedNoticePage = style([
  noticePage,
  mapOverlay,
  {
    padding: 0,
    alignItems: "flex-end",
    backgroundColor: "transparent",
  },
]);
globalStyle(`${sharedNoticePage} > :last-child`, {
  flex: "none",
  width: "100%",
  padding: "20px 18px calc(20px + env(safe-area-inset-bottom, 0px))",
  border: 0,
  borderRadius: `${vars.radius.boardSheet} ${vars.radius.boardSheet} 0 0`,
  background: vars.color.surface,
  pointerEvents: "auto",
});
globalStyle(`${sharedNoticePage} [data-native-map-hit]`, { pointerEvents: "auto" });
globalStyle(`${sharedNoticePage}[data-station-sheet] > :last-child`, { overflowY: "auto" });
globalStyle(`${sharedNoticePage}[data-station-sheet="summary"] > :last-child`, { height: "30dvh" });
globalStyle(`${sharedNoticePage}[data-station-sheet="detail"] > :last-child`, {
  height: `${BOARD_SHEET_EXPANDED_PERCENT}dvh`,
});
globalStyle(`${sharedNoticePage}[data-station-sheet="full"] > :last-child`, {
  height: `calc(100dvh - ${SAFE_AREA_TOP})`,
});
