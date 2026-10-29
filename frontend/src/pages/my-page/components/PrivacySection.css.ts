import { style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const section = style({
  display: "flex",
  flexDirection: "column",
  marginTop: 32,
  fontFamily: "'Noto Sans KR', 'Apple SD Gothic Neo', sans-serif",
  color: "#292A2D",
});

export const heading = style({ margin: "0 0 4px", fontSize: 17, fontWeight: 700, lineHeight: "25px" });

export const row = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "space-between",
  gap: 12,
  minHeight: 48,
  fontSize: 14,
  cursor: "pointer",
});

export const track = style({ position: "relative", flexShrink: 0, width: 46, height: 28 });

export const input = style({
  appearance: "none",
  position: "absolute",
  inset: 0,
  width: "100%",
  height: "100%",
  margin: 0,
  borderRadius: 14,
  background: "#D7D0C8",
  cursor: "inherit",
  transition: "background-color 150ms linear",
  selectors: {
    "&:checked": { background: vars.color.brand },
    "&:focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 3 },
  },
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});

export const thumb = style({
  position: "absolute",
  top: 3,
  left: 3,
  width: 22,
  height: 22,
  borderRadius: "50%",
  background: "#FFFFFF",
  boxShadow: "0 1px 3px rgba(0, 0, 0, 0.2)",
  pointerEvents: "none",
  transition: "transform 150ms linear",
  selectors: { [`${input}:checked + &`]: { transform: "translateX(18px)" } },
  "@media": { "(prefers-reduced-motion: reduce)": { transition: "none" } },
});

export const description = style({
  margin: 0,
  color: "#68645F",
  fontSize: 12,
  lineHeight: 1.7,
  wordBreak: "keep-all",
  overflowWrap: "anywhere",
});
