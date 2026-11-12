import { keyframes, style } from "@vanilla-extract/css";
import { vars } from "@/shared/styles/tokens.css";

export const guestCard = style({
  display: "flex",
  flexDirection: "column",
  alignItems: "center",
  gap: 11,
  width: "100%",
  maxWidth: 356,
  margin: "0 auto",
  padding: "8px 16px 24px",
  textAlign: "center",
  "@media": { "(max-width: 359px), (max-height: 710px)": { paddingBlock: "4px 14px", gap: 9 } },
});
export const guestTitle = style({
  margin: 0,
  color: "#292A2D",
  fontSize: 22,
  fontWeight: 700,
  lineHeight: "32px",
  wordBreak: "keep-all",
});
export const guestDescription = style({
  width: "100%",
  margin: 0,
  color: "#76706A",
  fontSize: 14,
  lineHeight: 1.6,
  wordBreak: "keep-all",
});
export const guestLogin = style({
  selectors: { "&&": { borderRadius: 14, background: vars.color.liveBus, fontWeight: 700 } },
});

export const mascotScene = style({
  position: "relative",
  width: 112,
  height: 88,
  pointerEvents: "none",
  "::before": {
    content: '""',
    position: "absolute",
    inset: "11px 11px 9px 9px",
    borderRadius: "54% 46% 47% 53% / 57% 53% 47% 43%",
    background: "#FCE8DF",
  },
});

export const wake = style({
  position: "absolute",
  inset: 0,
  width: 112,
  height: 88,
  color: "#DD907D",
  opacity: 0.68,
});

const bob = keyframes({
  "0%, 100%": { transform: "rotate(-3deg)" },
  "50%": { transform: "translateY(-3px) rotate(3deg)" },
});
export const mascot = style({
  position: "absolute",
  top: 7,
  left: 23,
  objectFit: "contain",
  transformOrigin: "50% 75%",
  "@media": { "(prefers-reduced-motion: no-preference)": { animation: `${bob} 3.2s ease-in-out infinite` } },
});

const orbit = keyframes({ to: { offsetDistance: "100%" } });
export const mascotOrbitPath = "M24 18C44 3 82 3 101 26C120 50 90 76 56 78C24 79 2 63 8 40C11 29 17 23 24 18Z";
const starSway = keyframes({
  "0%, 100%": { transform: "rotate(-10deg)" },
  "50%": { transform: "translateY(-2px) rotate(10deg)" },
});
export const orbitingStar = style({
  position: "absolute",
  top: 8,
  left: 76,
  width: 19,
  height: 19,
  "@supports": {
    "(offset-path: path('M 0 0 L 1 1'))": {
      top: 0,
      left: 0,
      offsetPath: `path("${mascotOrbitPath}")`,
      offsetRotate: "0deg",
      offsetDistance: "25%",
      "@media": {
        "(prefers-reduced-motion: no-preference)": {
          offsetDistance: 0,
          animation: `${orbit} 5s linear infinite, ${starSway} 2.5s ease-in-out infinite`,
        },
      },
    },
  },
});
