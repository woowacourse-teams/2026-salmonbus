import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const button = style({
  display: "flex",
  alignItems: "center",
  justifyContent: "center",
  gap: 6,
  padding: "9px 14px",
  border: "1px solid #F0C2B8",
  borderRadius: 20,
  background: vars.color.surface,
  color: "#C34F3D",
  font: "inherit",
  fontSize: 13,
  fontWeight: 600,
  cursor: "pointer",
  ":disabled": { cursor: "default", opacity: 0.7 },
  ":focus-visible": { outline: `3px solid ${vars.color.focusRing}`, outlineOffset: 4 },
});

const spin = keyframes({ to: { transform: "rotate(360deg)" } });

export const spinning = style({
  animation: `${spin} 900ms linear infinite`,
  "@media": { "(prefers-reduced-motion: reduce)": { animation: "none" } },
});
