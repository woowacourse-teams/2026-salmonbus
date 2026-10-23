import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const summary = style({
  display: "flex",
  alignItems: "center",
  gap: "12px",
  minHeight: "56px",
  // 헤더의 공통 간격 10px에 더해 방향 버튼과는 14px을 띄운다.
  marginBottom: "4px",
});

export const routeNumber = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  flexShrink: 0,
  minWidth: "85px",
  height: "50px",
  margin: 0,
  padding: "0 10px",
  borderRadius: vars.radius.control,
  backgroundColor: vars.color.liveBus,
  color: vars.color.onInk,
  fontFamily: "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif",
  fontSize: "25px",
  fontWeight: 800,
  lineHeight: "30px",
  letterSpacing: "-0.8px",
  whiteSpace: "nowrap",
});

export const content = style({
  display: "flex",
  flex: 1,
  flexDirection: "column",
  minWidth: 0,
  gap: "7px",
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  containerName: "route-summary",
  containerType: "inline-size",
});

export const endpoints = style({
  margin: 0,
  color: vars.color.routeSummary.endpoints,
  fontSize: "13px",
  fontWeight: 600,
  lineHeight: "19px",
  overflowWrap: "anywhere",
});

export const details = style({
  display: "flex",
  flexWrap: "wrap",
  alignItems: "center",
  gap: "4px 8px",
  margin: 0,
});

export const detail = style({
  display: "flex",
  alignItems: "center",
  minHeight: "16px",
  selectors: {
    "& + &::before": {
      content: '""',
      flexShrink: 0,
      width: "2px",
      height: "2px",
      marginRight: "8px",
      borderRadius: "1px",
      backgroundColor: vars.color.routeSummary.separator,
    },
  },
  "@container": {
    "route-summary (max-width: 245px)": {
      selectors: { "& + &::before": { display: "none" } },
    },
  },
});

export const label = style({
  position: "absolute",
  width: "1px",
  height: "1px",
  padding: 0,
  margin: "-1px",
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap",
  border: 0,
});

export const summaryIcon = style({
  width: "12px",
  height: "12px",
  flexShrink: 0,
  // 글자의 시각적 중심이 line box보다 아래에 있어 아이콘도 같은 높이로 맞춘다.
  transform: "translateY(1px)",
});

export const value = style({
  display: "flex",
  alignItems: "center",
  gap: "3px",
  margin: 0,
  color: vars.color.routeSummary.text,
  fontSize: "11px",
  fontWeight: 500,
  lineHeight: 1,
  whiteSpace: "nowrap",
  fontVariantNumeric: "tabular-nums",
});
