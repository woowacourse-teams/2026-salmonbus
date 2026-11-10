/* Hallmark · shape: app screen · screen: RouteNetworkPage · genre: modern-minimal · theme: 연어버스 · redesign: full · signature: 종점을 잇는 코랄 노선 표식 · v2.0.0 */
/* Hallmark · pre-emit critique: P4 H4 E4 S3 R5 V4 · 종점 두 곳을 세로 노선 표식에 맞춰 일반 목록과 구분한다. */
/* Hallmark · waive 26b · 노선도 재시도 버튼은 오류 상태에서만 표시된다 · RouteNetworkPage의 재시도 버튼은 loading 상태로 바뀌면 스켈레톤으로 교체된다. */
import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";
import { tabSheetBody } from "./home/HomeScreen.css";

export const page = style({});
globalStyle(`${tabSheetBody}:has(> ${page})`, { paddingBlockStart: 6 });

const cardInset = 10;
const compactCardInset = 9;
const compactWidth = "(max-width: 404px)";

export const heading = style({
  margin: 0,
  paddingInlineStart: cardInset + 1,
  color: vars.color.textStrong,
  fontSize: 16,
  fontWeight: 700,
  "@media": { [compactWidth]: { paddingInlineStart: compactCardInset + 1 } },
});
export const list = style({ display: "grid", gap: 8, margin: 0, padding: 0, listStyle: "none" });

const cardLayout = style({
  position: "relative",
  display: "grid",
  gridTemplateColumns: "76px minmax(0, 1fr) 14px",
  alignItems: "center",
  gap: 12,
  minHeight: 88,
  padding: `11px 13px 11px ${cardInset}px`,
  border: `1px solid ${vars.color.rowDivider}`,
  borderRadius: vars.radius.pill,
  background: vars.color.surface,
  "@media": {
    [compactWidth]: { gridTemplateColumns: "74px minmax(0, 1fr) 14px", gap: 8, paddingInline: compactCardInset },
  },
});
const controlFeedback = style({
  selectors: {
    "&:focus-visible": { outline: `2px solid ${vars.color.focusRing}`, outlineOffset: 2 },
    "&:active": { background: vars.color.pressedRowSurface },
  },
});
const endpointGap = 4;
export const card = style([
  cardLayout,
  controlFeedback,
  {
    color: vars.color.ink,
    textDecoration: "none",
  },
]);
export const routeNumber = style({
  gridColumn: 1,
  selectors: {
    "&&": {
      display: "grid",
      placeItems: "center",
      minHeight: 48,
      padding: "5px 6px",
      borderRadius: 8,
      fontSize: 22,
      lineHeight: 1.2,
      fontVariantNumeric: "tabular-nums",
      letterSpacing: "-0.5px",
      textAlign: "center",
      overflowWrap: "anywhere",
    },
  },
  "@media": { "(max-width: 359px)": { selectors: { "&&": { fontSize: 20 } } } },
});
export const longRouteNumber = style({
  selectors: { "&&&": { fontSize: 19, paddingInline: 3 } },
  "@media": { "(max-width: 359px)": { selectors: { "&&&": { fontSize: 18 } } } },
});
export const endpoints = style({
  gridColumn: 2,
  display: "grid",
  gridTemplateRows: "repeat(2, minmax(20px, 1fr))",
  gap: endpointGap,
  minWidth: 0,
});
const endpointText = style({
  minWidth: 0,
  paddingInlineStart: 14,
  color: vars.color.textBody,
  fontSize: 13,
  fontWeight: 400,
  lineHeight: 1.35,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
});
const endpoint = style([
  endpointText,
  {
    position: "relative",
    alignSelf: "stretch",
    display: "flex",
    alignItems: "center",
    selectors: {
      "&::before": {
        content: '""',
        position: "absolute",
        top: "50%",
        left: 0,
        width: 9,
        height: 9,
        borderRadius: "50%",
        border: `1.5px solid ${vars.color.brand}`,
        background: vars.color.surface,
        translate: "0 -50%",
        zIndex: 1,
      },
    },
  },
]);
export const startEndpoint = style([
  endpoint,
  {
    gridRow: 1,
    "::after": {
      content: '""',
      position: "absolute",
      top: "50%",
      left: 4,
      width: 1,
      height: `calc(100% + ${endpointGap}px)`,
      background: vars.color.brand,
    },
  },
]);
export const endEndpoint = style([endpoint, { gridRow: 2, gap: 3, "::before": { background: vars.color.brand } }]);
const singleLineEndpoint = style({ minWidth: 0, overflow: "hidden", whiteSpace: "nowrap", textOverflow: "ellipsis" });
export const endpointName = style([
  singleLineEndpoint,
  {
    flexShrink: 0,
    maxWidth: "100%",
    selectors: { "&:not(:last-child)": { maxWidth: "calc(100% - 13px)" } },
  },
]);
export const endpointDetails = style([
  singleLineEndpoint,
  { minWidth: 10, color: vars.color.textSubtle, fontSize: 10 },
]);
export const open = style({
  gridColumn: 3,
  display: "block",
  width: "100%",
  color: vars.color.textSubtle,
});
export const notice = style({ color: vars.color.textSubtle, fontSize: 14 });
export const skeleton = style([cardLayout, { color: vars.color.mutedChipSurface }]);
export const placeholder = style({
  display: "block",
  height: 10,
  borderRadius: vars.radius.badge,
  background: "currentColor",
});
export const placeholderNumber = style([placeholder, { width: "100%", height: 48, borderRadius: 8 }]);
export const placeholderEndpoints = style({ display: "grid", gap: 12 });
export const retry = style([
  controlFeedback,
  {
    minHeight: 44,
    padding: "0 16px",
    border: `1px solid ${vars.color.rowDivider}`,
    borderRadius: vars.radius.control,
    background: vars.color.surface,
    color: vars.color.textStrong,
    cursor: "pointer",
    "@media": { "(hover: hover)": { selectors: { "&:hover": { borderColor: vars.color.brand } } } },
  },
]);
