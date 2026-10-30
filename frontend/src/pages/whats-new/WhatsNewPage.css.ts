import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const page = style({ fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif" });

export const backTitle = style({ display: "flex", alignItems: "center", gap: 12, marginBottom: 28 });

export const backIcon = style({
  display: "grid",
  placeItems: "center",
  flexShrink: 0,
  width: 32,
  height: 32,
  border: 0,
  borderRadius: "50%",
  backgroundColor: vars.color.liveBus,
  color: vars.color.onBrand,
  textDecoration: "none",
});

export const title = style({ margin: 0, fontSize: 25, fontWeight: 700 });

export const caption = style({
  margin: 0,
  color: "#68645F",
  fontSize: 12,
  lineHeight: 1.6,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
});

export const empty = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 6,
  padding: "40px 0",
  textAlign: "center",
});

export const emptyTitle = style({ margin: 0, fontSize: 15, fontWeight: 600, lineHeight: 1.6 });

export const notes = style({ display: "grid", gap: 12 });

export const note = style({
  padding: 17,
  border: "1px solid #EAE6E0",
  borderRadius: 14,
  background: vars.color.surface,
});

export const noteDate = style({ margin: "0 0 8px", fontSize: 15, fontWeight: 700, lineHeight: "22px" });

export const features = style({
  display: "grid",
  gap: 6,
  margin: 0,
  paddingLeft: 18,
  fontSize: 14,
  lineHeight: 1.5,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
});
