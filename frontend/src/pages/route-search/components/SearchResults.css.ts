import { keyframes, style } from "@vanilla-extract/css";
import { ROUTE_ROW_INSET } from "@/shared/styles/routeRow";
import { vars } from "@/shared/styles/tokens.css";

const revealAfterDelay = keyframes({
  from: { visibility: "hidden" },
});

export const status = style({
  position: "absolute",
  width: "1px",
  height: "1px",
  overflow: "hidden",
  clipPath: "inset(50%)",
  whiteSpace: "nowrap",
});

export const emptyNotice = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  marginTop: "120px",
  textAlign: "center",
});

export const emptyTitle = style({
  margin: 0,
  color: vars.color.textStrong,
  fontSize: "16px",
  fontWeight: 700,
});

export const emptyDescription = style({
  margin: "8px 0 0",
  color: vars.color.textSubtle,
  fontSize: "13px",
});

export const notice = style({
  margin: "20px 4px 0",
  color: vars.color.textSubtle,
  fontSize: "14px",
  fontWeight: 600,
});

export const loadingNotice = style([notice, { animation: `${revealAfterDelay} 0s 200ms both` }]);

export const retryButton = style({
  minHeight: "44px",
  marginTop: "8px",
  padding: "0 18px",
  border: `1px solid ${vars.color.cardBorder}`,
  borderRadius: "22px",
  background: "transparent",
  color: vars.color.textStrong,
  fontWeight: 700,
});

export const section = style({
  minHeight: 0,
  overflowY: "auto",
  margin: `16px -${ROUTE_ROW_INSET} 0`,
});

export const sectionTitle = style({
  margin: "0 0 4px",
  padding: `0 ${ROUTE_ROW_INSET}`,
  color: vars.color.textBody,
  fontSize: "13px",
  fontWeight: 600,
});

export const list = style({
  margin: 0,
  padding: 0,
  listStyle: "none",
});
