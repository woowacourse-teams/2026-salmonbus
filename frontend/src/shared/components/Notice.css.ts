import { keyframes, style } from "@vanilla-extract/css";

const enter = keyframes({
  from: { opacity: 0, transform: "translate(-50%, 24px)" },
  to: { opacity: 1, transform: "translate(-50%, 0)" },
});

export const toast = style({
  position: "fixed",
  bottom: "calc(78px + env(safe-area-inset-bottom, 0px))",
  left: "50%",
  transform: "translateX(-50%)",
  width: "calc(100% - 64px)",
  maxWidth: 356,
  zIndex: 30,
  padding: "13px 16px",
  borderRadius: 12,
  background: "#292A2DEE",
  color: "white",
  fontFamily: "'Noto Sans KR', sans-serif",
  fontSize: 12,
  fontWeight: 500,
  lineHeight: 1.5,
  textAlign: "center",
  pointerEvents: "none",
  animation: `${enter} 280ms cubic-bezier(0.22, 1, 0.36, 1) both`,
  "@media": { "(prefers-reduced-motion: reduce)": { animation: "none" } },
});
