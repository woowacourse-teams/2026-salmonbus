import { createVar, globalStyle, keyframes, style } from "@vanilla-extract/css";
import { standardVerdictChipWidth } from "@/shared/components/VerdictChip.css";
import { vars } from "@/shared/styles/tokens.css";
import { tabPageInset } from "@/shared/styles/tabPageSpacing";

const ink = vars.color.textStrong;
const muted = vars.color.textSubtle;
export const navigationHeight = 94;
export const stopSheetViewportRatio = 0.6;
export const handleHeight = 38;
const navigationSpace = createVar();
export const navigationInset = `calc(${navigationSpace} + env(safe-area-inset-bottom, 0px))`;
const searchInset = "max(28px, calc(12px + env(safe-area-inset-top, 0px)))";
const searchHeight = 58;
const searchClearance = `calc(${searchInset} + ${searchHeight}px + 12px)`;
const mapControlInset = 12;
const sheetHeaderInset = 22;
export const sheetHeight = "--home-sheet-height";
const sheetHeightValue = `var(${sheetHeight}, 0px)`;
const sheetObstruction = createVar();
export const page = style({
  position: "relative",
  width: "100%",
  maxWidth: 425,
  height: "100dvh",
  overflow: "hidden",
  margin: "0 auto",
  background: vars.color.surface,
  color: ink,
  selectors: { 'html[data-shared-native-map-ready="true"] &': { background: "transparent" } },
  "@media": { "(max-width: 599.98px)": { maxWidth: "none" } },
});
export const main = style({
  position: "relative",
  height: "100%",
  display: "flex",
  flexDirection: "column",
  isolation: "isolate",
  vars: {
    [navigationSpace]: `${navigationHeight}px`,
    [sheetObstruction]: `max(${navigationInset}, ${sheetHeightValue})`,
  },
  selectors: {
    '&[data-station-open="true"]': {
      vars: { [navigationSpace]: "0px", [sheetObstruction]: `calc(${navigationInset} + ${sheetHeightValue})` },
    },
  },
});
export const brandHeading = style({ margin: 0, flexShrink: 0 });
export const content = style({
  position: "relative",
  flex: 1,
  minHeight: 0,
});
export const floatingSearch = style({
  position: "absolute",
  inset: `${searchInset} 4px auto`,
  zIndex: 3,
  display: "flex",
  alignItems: "center",
  gap: 10,
  boxSizing: "border-box",
  height: searchHeight,
  padding: "0 17px",
  border: "1px solid #FFFFFFCC",
  borderRadius: 99,
  background: "linear-gradient(180deg, #FFFFFF48, #FFFFFF06), #FFFFFF4D",
  backdropFilter: "blur(24px)",
  WebkitBackdropFilter: "blur(24px)",
  boxShadow: "inset 0 1px 1px #FFFFFFB3, 0 8px 28px #3B2A1B1F",
  selectors: { "&[hidden]": { display: "none" } },
  "@media": { "(max-width: 374px)": { gap: 9, paddingInline: 15 } },
});
export const searchDivider = style({ width: 1, height: 18, flexShrink: 0, background: "#854D3D30" });
export const map = style({
  zIndex: 0,
  position: "absolute",
  inset: 0,
  background: vars.color.background,
  selectors: { 'html[data-shared-native-map-ready="true"] &': { visibility: "hidden" } },
});
export const mapCanvas = style({
  width: "100%",
  height: "100%",
  isolation: "isolate",
  translate: "0 calc(-1 * var(--board-map-crop-inset, 0px))",
});
export const searchOverlay = style({ position: "absolute", inset: 0, zIndex: 5 });
globalStyle(`${searchOverlay} > div`, { height: "100%" });
export const routeNetwork = style({
  position: "absolute",
  top: 0,
  left: 0,
  overflow: "visible",
  strokeLinecap: "round",
  strokeLinejoin: "round",
  pointerEvents: "none",
});
export const routeRevealCenter = "--route-reveal-center";
export const routeRevealRadius = "--route-reveal-radius";
const revealRoutes = keyframes({
  from: { clipPath: `circle(0px at var(${routeRevealCenter})) view-box` },
  to: { clipPath: `circle(var(${routeRevealRadius}) at var(${routeRevealCenter})) view-box` },
});
export const routeReveal = style({
  "@media": { "(prefers-reduced-motion: no-preference)": { animation: `${revealRoutes} 720ms ease-out` } },
});
const sweepRoutes = keyframes({
  "0%": { transform: "translateX(-25%)", opacity: 0 },
  "14%, 56%": { opacity: 1 },
  "72%, 100%": { transform: "translateX(104%)", opacity: 0 },
});
export const routeNetworkGlow = style({
  transformBox: "view-box",
  animation: `${sweepRoutes} 8s linear infinite`,
  "@media": { "(prefers-reduced-motion: reduce)": { display: "none", animation: "none" } },
});
// 지도는 탭바 아래까지 이어지고 SDK 로고는 시트 위에 보인다.
globalStyle(`${mapCanvas} > div:has(img[alt="NAVER"])`, {
  translate: `0 calc(-1 * ${sheetObstruction})`,
});
globalStyle(`${main}[data-board-open="true"] ${mapCanvas} > div:has(img[alt="NAVER"])`, {
  translate: "0 calc(-1 * var(--board-map-crop-inset, 0px))",
});
export const mapNotice = style({
  position: "absolute",
  top: searchClearance,
  left: 16,
  right: 72,
  padding: 12,
  borderRadius: 14,
  margin: 0,
  background: "#FFFFFFF2",
  fontSize: 12,
  lineHeight: 1.5,
  color: muted,
});
export const favoriteMapNotice = style([
  mapNotice,
  { top: "auto", bottom: `calc(${sheetObstruction} + ${mapControlInset}px)`, zIndex: 2 },
]);
export const stationMapError = style([
  mapNotice,
  {
    right: 16,
    zIndex: 3,
    display: "flex",
    flexWrap: "wrap",
    alignItems: "center",
    justifyContent: "space-between",
    gap: 8,
  },
]);
export const stopLoadError = style({ display: "grid", justifyItems: "start", gap: 8 });
export const locate = style({
  right: mapControlInset,
  bottom: `calc(${mapControlInset}px + ${sheetObstruction})`,
});
export const locationMessage = style({
  position: "absolute",
  bottom: `calc(${sheetObstruction} + ${mapControlInset}px)`,
  left: 16,
  right: 68,
  zIndex: 3,
  margin: 0,
  padding: 12,
  borderRadius: 12,
  background: "#FFFFFF",
  fontSize: 11,
  lineHeight: 1.5,
  boxShadow: "0 3px 16px #3B2A1B10",
});
export const sheetSurface = style({
  position: "absolute",
  bottom: 0,
  width: "100%",
  maxHeight: "calc(100% - env(safe-area-inset-top, 0px))",
  paddingBottom: navigationInset,
  zIndex: 4,
  display: "flex",
  flexDirection: "column",
  overflow: "hidden",
  borderRadius: `${vars.radius.boardSheet} ${vars.radius.boardSheet} 0 0`,
  background: "#FFFFFF",
  boxShadow: "0 -6px 28px #3B2A1B12",
  "@media": {
    "(prefers-reduced-motion: no-preference)": {
      transition: "translate 300ms cubic-bezier(.22,.8,.22,1)",
      "@starting-style": { translate: "0 100%" },
    },
  },
  selectors: {
    '&[data-full="true"]': { borderRadius: 0 },
    '&[data-tab-sheet="true"]': { paddingBottom: 0 },
    '&[data-empty="true"]': { display: "none" },
  },
});
export const sheet = style({
  display: "flex",
  flexDirection: "column",
  minHeight: 0,
  overflow: "hidden",
  transition: "height 300ms cubic-bezier(.22,.8,.22,1)",
  selectors: { '&[data-dragging="true"]': { transition: "none" } },
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});
export const sheetHeader = style({
  position: "relative",
  isolation: "isolate",
  flexShrink: 0,
  padding: `0 ${sheetHeaderInset}px`,
  touchAction: "none",
  userSelect: "none",
  cursor: "grab",
});
export const handle = style({
  height: handleHeight,
  flexShrink: 0,
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  width: "100%",
  cursor: "ns-resize",
  borderRadius: 12,
  selectors: { "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}` } },
});
export const handleBar = style({
  width: 34,
  height: 4,
  background: vars.color.axisLine,
  borderRadius: 4,
  selectors: { [`${handle}[data-close-visible="true"] &`]: { translate: "0 -5px" } },
});
export const homeHandle = style([
  handle,
  {
    height: 32,
    "@media": { "(max-height: 720px)": { height: 28 } },
  },
]);
const sheetActionsTopOffset = -10;

export const sheetActions = style({
  position: "relative",
  display: "flex",
  justifyContent: "flex-end",
  marginTop: sheetActionsTopOffset,
});
export const sheetActionsWithTitle = style([
  sheetActions,
  {
    marginTop: 0,
    alignItems: "center",
    justifyContent: "space-between",
    paddingInlineStart: `calc(${tabPageInset} - ${sheetHeaderInset}px)`,
  },
]);
export const closeSheet = style({
  flexShrink: 0,
  display: "grid",
  placeItems: "center",
  width: 28,
  height: 28,
  padding: 0,
  border: 0,
  borderRadius: "50%",
  background: vars.color.mutedChipSurface,
  color: vars.color.textStrong,
  cursor: "pointer",
  selectors: {
    [`${sheetActionsWithTitle} &`]: { translate: `0 ${sheetActionsTopOffset}px` },
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: 2 },
  },
});
export const sheetBody = style({
  minHeight: 0,
  flex: 1,
  overflowY: "auto",
  overscrollBehaviorY: "contain",
  padding: "0 22px 10px",
  scrollbarWidth: "thin",
  scrollbarColor: `${vars.color.rowDivider} transparent`,
});
export const tabSheetBody = style([
  sheetBody,
  {
    paddingBlockStart: 16,
    paddingBlockEnd: `calc(${navigationInset} + 10px)`,
    paddingInline: tabPageInset,
  },
]);
export const homeSheetBody = style([
  sheetBody,
  {
    paddingBlockEnd: `calc(${navigationInset} - 6px)`,
  },
]);
const probe = style({ position: "absolute", width: 0, visibility: "hidden", pointerEvents: "none" });
export const safeTop = style([probe, { height: "env(safe-area-inset-top, 0px)" }]);
export const safeBottom = style([probe, { height: "env(safe-area-inset-bottom, 0px)" }]);
export const note = style({ margin: "14px 0 0", fontSize: 11, lineHeight: 1.8, color: muted });
export const stopHeading = style({
  margin: "4px 0 10px",
  minWidth: 0,
  lineHeight: 1.35,
  fontSize: 21,
  fontWeight: 600,
  letterSpacing: -0.7,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
  outline: "none",
});

export const stopRouteList = style({ display: "grid", rowGap: 4, margin: 0, padding: 0, listStyle: "none" });
export const stopRouteLink = style({
  display: "grid",
  gridTemplateColumns: "minmax(0, 1fr) 96px 30px",
  alignItems: "center",
  gap: 8,
  padding: "8px 4px 8px 0",
  minHeight: 48,
  borderBlockEnd: "1px solid #F0F0F0",
  color: vars.color.textStrong,
  textDecoration: "none",
  selectors: {
    [`${stopRouteList} > li:only-child > &`]: { borderBlockEnd: 0 },
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: -2 },
  },
  "@media": {
    "(max-width: 359px)": {
      gridTemplateColumns: "minmax(0, 1fr) 88px 28px",
      gap: 6,
      paddingInlineEnd: 2,
    },
  },
});
export const stopRouteSummary = style({
  display: "grid",
  gridTemplateColumns: "86px minmax(0, 1fr)",
  alignItems: "center",
  minWidth: 0,
  columnGap: 12,
  "@media": {
    "(max-width: 404px)": { columnGap: 8 },
    "(max-width: 359px)": { gridTemplateColumns: "80px minmax(0, 1fr)", columnGap: 6 },
  },
});
export const stopRouteNumberSlot = style({
  display: "flex",
  minWidth: 0,
});
export const stopRouteNumber = style({
  minWidth: 42,
  maxWidth: "100%",
  padding: "4px 7px",
  borderRadius: 6,
  background: "#FCEDE7",
  color: "#A43C2D",
  textAlign: "center",
  fontSize: 15,
  fontWeight: 750,
  fontVariantNumeric: "tabular-nums",
  overflowWrap: "anywhere",
  "@media": { "(max-width: 359px)": { paddingInline: 4 } },
  selectors: {
    '&[data-route-tone="city"]': { background: "#EAF1FC", color: "#285DB7" },
    '&[data-route-tone="village"]': { background: "#E6F2EA", color: "#26704A" },
    '&[data-route-tone="airport"]': { background: "#E1F4FB", color: "#17465B" },
    '&[data-route-tone="unknown"]': { background: vars.color.mutedChipSurface, color: vars.color.textBody },
  },
});
export const stopRouteArrival = style({
  flexShrink: 0,
  whiteSpace: "nowrap",
  color: vars.color.textSubtle,
  fontSize: 11,
  fontVariantNumeric: "tabular-nums",
  selectors: {
    '&[data-has-time="true"]': { color: vars.color.textStrong, fontSize: 13, fontWeight: 700 },
    '&[data-has-time="false"]': { whiteSpace: "normal", wordBreak: "keep-all" },
  },
});
export const stopRouteTiming = style({
  display: "grid",
  gridTemplateColumns: "minmax(0, 1fr)",
  rowGap: 3,
});
export const stopRouteDistance = style({
  color: vars.color.textSubtle,
  fontSize: 11,
  whiteSpace: "nowrap",
});

export const stopRouteVerdict = style({
  display: "flex",
  inlineSize: standardVerdictChipWidth,
  justifyContent: "center",
});
export const stopRouteArrow = style({
  display: "grid",
  placeItems: "center",
  justifySelf: "end",
  width: 22,
  height: 22,
  borderRadius: "50%",
  background: vars.color.background,
  color: vars.color.textBody,
});
