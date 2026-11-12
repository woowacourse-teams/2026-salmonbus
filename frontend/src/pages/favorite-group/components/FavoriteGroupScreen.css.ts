import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const screen = style({
  maxWidth: 356,
  margin: "0 auto",
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  color: "#292A2D",
});
export const title = style({ display: "flex", alignItems: "center", gap: 10, margin: "0 0 16px", paddingRight: 12 });
export const loadError = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  justifyContent: "center",
  gap: 32,
  // 본문 여백 48px, 제목 영역 51px, 하단 메뉴 공간 100px을 제외한다.
  minHeight: "calc(100dvh - 199px - env(safe-area-inset-top, 0px) - env(safe-area-inset-bottom, 0px))",
  textAlign: "center",
});
export const loadNotice = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
});
export const titleLabel = style({ display: "flex", alignItems: "center", gap: 8, flex: 1, minWidth: 0 });
export const name = style({
  minWidth: 0,
  overflowWrap: "anywhere",
  margin: 0,
  fontSize: 24,
  lineHeight: "35px",
  fontWeight: 700,
});
export const rename = style({
  flexShrink: 0,
  padding: 0,
  border: 0,
  background: "transparent",
  color: vars.color.liveBus,
  font: "inherit",
  fontSize: 12,
  fontWeight: 600,
  lineHeight: "17px",
  cursor: "pointer",
});
export const back = style({
  padding: 0,
  border: 0,
  cursor: "pointer",
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 32,
  height: 32,
  borderRadius: "50%",
  background: vars.color.liveBus,
  color: "white",
  textDecoration: "none",
});
