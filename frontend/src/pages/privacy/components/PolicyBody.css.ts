import { globalStyle, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const body = style({
  color: "#292A2D",
  fontSize: 14,
  lineHeight: 1.7,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
});

globalStyle(`${body} h2`, { margin: "40px 0 12px", fontSize: 17, fontWeight: 700, lineHeight: "25px" });
globalStyle(`${body} h3`, { margin: "28px 0 10px", fontSize: 15, fontWeight: 700, lineHeight: 1.5 });
globalStyle(`${body} h4`, { margin: "20px 0 8px", fontSize: 14, fontWeight: 700, lineHeight: 1.5 });
globalStyle(`${body} :is(h2, h3, h4)`, { scrollMarginTop: 16 });
globalStyle(`${body} :is(h2, h3, h4):focus:not(:focus-visible)`, { outline: "none" });

globalStyle(`${body} p`, { margin: "0 0 12px" });
globalStyle(`${body} :is(ul, ol)`, { margin: "0 0 12px", paddingLeft: 20 });
globalStyle(`${body} li + li`, { marginTop: 4 });
globalStyle(`${body} li > ul`, { margin: "4px 0 0" });

globalStyle(`${body} a`, {
  color: "inherit",
  textDecorationColor: "#C9C4BC",
  textUnderlineOffset: 3,
});

globalStyle(`${body} code`, {
  padding: "1px 4px",
  borderRadius: 4,
  background: "#EFEBE6",
  fontFamily: "ui-monospace, SFMono-Regular, Menlo, monospace",
  fontSize: "0.9em",
});

globalStyle(`${body} blockquote`, {
  display: "flow-root",
  margin: "0 0 16px",
  padding: "12px 14px 0",
  borderLeft: `3px solid ${vars.color.rowDivider}`,
  borderRadius: "0 8px 8px 0",
  background: "#F3F0EC",
});

export const tableScroller = style({
  margin: "0 0 16px",
  overflowX: "auto",
  selectors: {
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 2 },
  },
});

globalStyle(`${tableScroller} table`, {
  width: "100%",
  borderCollapse: "collapse",
  fontSize: 13,
  lineHeight: 1.6,
});

globalStyle(`${tableScroller} :is(th, td)`, {
  minWidth: "7em",
  padding: "8px 10px",
  border: `1px solid ${vars.color.rowDivider}`,
  textAlign: "left",
  verticalAlign: "top",
});

globalStyle(`${tableScroller} th`, { background: "#F2EFEB", fontWeight: 700 });

// 쿠키와 저장소 이름이 중간에서 끊기면 읽고 옮겨 적기 어렵다. 표는 자기 칸 안에서 옆으로 넘기므로 넓어져도 된다.
globalStyle(`${tableScroller} code`, { whiteSpace: "nowrap" });
